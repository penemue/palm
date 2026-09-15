/**
 * Copyright 2026 Vyacheslav Lukianov (https://github.com/penemue)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.penemue.palm.lz77

import com.github.penemue.palm.CompressionProvider
import com.github.penemue.palm.vlq.writeVarInt
import java.io.ByteArrayInputStream

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every LZ77 provider, covering each parser.
 */
private val LZ77_PROVIDER_IDS = listOf(
    "lz77-greedy",
    "lz77-lazy",
)

/**
 * Byte value that separates planted runs, so a match can only ever stop at it.
 */
private const val SEPARATOR = 120

/**
 * Second byte value that separates planted runs, for tests that need two distinct separators.
 */
private const val SECOND_SEPARATOR = 121

/**
 * Byte value prefixing a planted match key.
 */
private const val LEADING_BYTE = 122

/**
 * Byte handed to a consumer as a match's last byte where only the length field is under test, so
 * the value can never reach the assertions.
 */
private const val LAST_BYTE_UNDER_TEST = 0

/**
 * Builds a run of [length] bytes starting at [first].
 *
 * While `first + length` stays within `256` the bytes are pairwise distinct and the run repeats
 * nowhere inside itself, so a reported match length is decided purely by where the planted copies of
 * the run start and end, and the run holds none of [SEPARATOR], [SECOND_SEPARATOR] and
 * [LEADING_BYTE]. Past that the values wrap and none of this holds.
 */
private fun distinctRun(length: Int, first: Int = 1): ByteArray = ByteArray(length) { (first + it).toByte() }

/**
 * Records `src[offset until offset + count]` as history one literal at a time.
 */
private fun Lz77Dictionary.advanceLiterals(src: ByteArray, offset: Int = 0, count: Int = src.size - offset) {
    for (i in offset until offset + count) {
        advanceLiteral(src[i])
    }
}

class Lz77Test {

    /**
     * Collects every emitted token so tests can assert on the raw stream directly.
     */
    private class RecordingConsumer(
        config: Lz77Config = Lz77Config(),
    ) : Lz77TokenConsumer(config, RepDistances(config.repDistanceCount)) {
        val tokens = mutableListOf<String>()

        override fun literal(value: Int) {
            tokens.add("L$value")
        }

        override fun match(length: Int, distance: Int, lastByte: Int) {
            tokens.add("M$length,$distance")
        }

        override fun finish() {}
    }

    /**
     * Encodes [data] with every LZ77 provider and reconstructs each payload, asserting a lossless
     * round trip.
     *
     * @param config geometry to compress with, or `null` to let each method use its default; the
     * decoder always rebuilds the geometry from the payload.
     */
    private fun roundTrip(data: ByteArray, config: Lz77Config? = null) {
        for (id in LZ77_PROVIDER_IDS) {
            val method = CompressionProvider.find(id).create()
            val packed = ByteArrayOutputStream()
            method.compress(ByteArrayInputStream(data), packed, config)
            val restored = ByteArrayOutputStream()
            method.decompress(ByteArrayInputStream(packed.toByteArray()), restored)
            assertContentEquals(data, restored.toByteArray(), "$id round trip mismatch for ${data.size} bytes")
        }
    }

    private fun tokensOf(data: ByteArray, config: Lz77Config): List<String> {
        val consumer = RecordingConsumer(config)
        data.lz77GreedyEncode(consumer, RepDistances(config.repDistanceCount))
        return consumer.tokens
    }

    private fun lazyTokensOf(data: ByteArray, config: Lz77Config): List<String> {
        val consumer = RecordingConsumer(config)
        data.lz77LazyEncode(consumer, RepDistances(config.repDistanceCount))
        return consumer.tokens
    }

    /**
     * Asserts that two dictionaries hold the same history, by querying both with [pattern] and
     * comparing what they report. [expected] must find a match.
     */
    private fun assertSameHistory(expected: Lz77Dictionary, actual: Lz77Dictionary, pattern: ByteArray) {
        val a = expected.longestMatch(pattern)
        val b = actual.longestMatch(pattern)
        assertTrue(a.isMatch, "the reference history should match the pattern")
        assertEquals(a.length, b.length, "reported length differs")
        assertEquals(a.distance, b.distance, "reported distance differs")
    }

    // ---- round-trip coverage of every parser ----------------------------------------------------

    @Test
    fun `empty input`() {
        roundTrip(ByteArray(0))
    }

    @Test
    fun `a windowBits the config rejects fails the decode`() {
        val method = CompressionProvider.find("lz77-greedy").create()
        val packed = ByteArrayOutputStream().also {
            method.compress(ByteArrayInputStream("repetitive repetitive repetitive".toByteArray()), it)
        }.toByteArray()
        // Common header is MAGIC[0..3] + family[4] + originalSize VLQ (one byte at 5 for this
        // 32-byte input); the LZ77 body then starts with windowBits at index 6. One past
        // MAX_WINDOW_BITS, so rebuilding the geometry fails before the decoder sizes its ring.
        val oversizedWindow = packed.copyOf().also { it[6] = (MAX_WINDOW_BITS + 1).toByte() }
        assertFailsWith<IOException> {
            method.decompress(ByteArrayInputStream(oversizedWindow), ByteArrayOutputStream())
        }
    }

    @Test
    fun `a repDistanceCount the config rejects fails the decode`() {
        val method = CompressionProvider.find("lz77-greedy").create()
        val packed = ByteArrayOutputStream().also {
            method.compress(ByteArrayInputStream("repetitive repetitive repetitive".toByteArray()), it)
        }.toByteArray()
        // The geometry sub-header follows the common header: windowBits at index 6, then lengthBits,
        // minMatch and repDistanceCount. Replace that last VLQ with 257 (two bytes: payload 1 with the
        // continuation bit, then 2), one past MAX_REP_DISTANCE_COUNT, so rebuilding the geometry fails
        // before any token is decoded.
        val oversizedRepCount =
            packed.copyOfRange(0, 9) + byteArrayOf(0x81.toByte(), 0x02) + packed.copyOfRange(10, packed.size)
        assertFailsWith<IOException> {
            method.decompress(ByteArrayInputStream(oversizedRepCount), ByteArrayOutputStream())
        }
    }

    @Test
    fun `a minMatch the config rejects fails the decode`() {
        val method = CompressionProvider.find("lz77-greedy").create()
        val packed = ByteArrayOutputStream().also {
            method.compress(ByteArrayInputStream("repetitive repetitive repetitive".toByteArray()), it)
        }.toByteArray()
        // The geometry sub-header follows the common header: windowBits at index 6, then lengthBits
        // and minMatch. Replace that last VLQ with one past MAX_MIN_MATCH: an unbounded minMatch
        // overflows the length a decoded match reports, so the geometry must be refused outright.
        val oversizedMinMatch = packed.copyOfRange(0, 8) +
                ByteArrayOutputStream().also { it.writeVarInt(MAX_MIN_MATCH + 1) }.toByteArray() +
                packed.copyOfRange(9, packed.size)
        assertFailsWith<IOException> {
            method.decompress(ByteArrayInputStream(oversizedMinMatch), ByteArrayOutputStream())
        }
    }

    @Test
    fun `custom geometry passed to compress round trips through the payload`() {
        val method = CompressionProvider.find("lz77-greedy").create()
        val config = Lz77Config(windowBits = 18, lengthBits = 6, minMatch = HASH_LENGTH)
        val data = ByteArray(40_000) { (it % 251).toByte() }
        val packed = ByteArrayOutputStream()
        method.compress(ByteArrayInputStream(data), packed, config)
        // Decompression takes no config: the payload carries the geometry the decoder needs.
        val restored = ByteArrayOutputStream()
        method.decompress(ByteArrayInputStream(packed.toByteArray()), restored)
        assertContentEquals(data, restored.toByteArray(), "custom-geometry round trip mismatch")
    }

    @Test
    fun `compress rejects a config from another family`() {
        val method = CompressionProvider.find("lz77-greedy").create()
        val alien = object : com.github.penemue.palm.CompressionConfig {}
        assertFailsWith<IllegalArgumentException> {
            method.compress(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), alien)
        }
    }

    @Test
    fun `single byte`() {
        roundTrip(byteArrayOf(42))
    }

    @Test
    fun `bytes below min match become literals`() {
        val config = Lz77Config()
        // One byte short of the shortest reportable match: nothing can be emitted as a match.
        val data = distinctRun(config.minMatch - 1)
        val tokens = tokensOf(data, config)
        assertEquals(data.size, tokens.size, tokens.toString())
        assertTrue(tokens.all { it.startsWith("L") }, tokens.toString())
    }

    @Test
    fun `all identical bytes round trip`() {
        roundTrip(ByteArray(10_000) { 'A'.code.toByte() })
    }

    @Test
    fun `repeating pattern`() {
        val unit = "abcabcabcXYZ".toByteArray()
        roundTrip(ByteArray(50_000) { unit[it % unit.size] })
    }

    @Test
    fun `text like data`() {
        val sentence = "the quick brown fox jumps over the lazy dog. ".toByteArray()
        val out = ByteArrayOutputStream()
        repeat(2_000) { out.write(sentence) }
        roundTrip(out.toByteArray())
    }

    @Test
    fun `incompressible random data still round trips`() {
        val data = ByteArray(20_000)
        Random(12345).nextBytes(data)
        roundTrip(data)
    }

    @Test
    fun `mixed random and repetitive`() {
        val rnd = Random(7)
        val out = ByteArrayOutputStream()
        repeat(100) {
            val chunk = ByteArray(rnd.nextInt(500))
            rnd.nextBytes(chunk)
            out.write(chunk)
            repeat(rnd.nextInt(50)) { out.write("PALMPALMPALM".toByteArray()) }
        }
        roundTrip(out.toByteArray())
    }

    @Test
    fun `interleaved periodic patterns round trip`() {
        // Two patterns of different periods woven together: matches alternate between two distances,
        // so most of them repeat a distance that is still remembered.
        val first = "the quick brown fox ".toByteArray()
        val second = "0123456789".toByteArray()
        val data = ByteArray(30_000) {
            if ((it / 64) % 2 == 0) first[it % first.size] else second[it % second.size]
        }
        roundTrip(data)
    }

    @Test
    fun `repeated distances round trip without remembering any`() {
        val data = ByteArray(30_000) { (it % 61).toByte() }
        roundTrip(data, Lz77Config(repDistanceCount = 0))
    }

    @Test
    fun `repeated distances round trip with a narrow rep list`() {
        val data = ByteArray(30_000) { (it % 61).toByte() }
        roundTrip(data, Lz77Config(repDistanceCount = 2))
    }

    @Test
    fun `repeated distances round trip with a single remembered distance`() {
        // One remembered distance leaves no rep index to write: the unary chain is empty.
        val data = ByteArray(30_000) { (it % 61).toByte() }
        roundTrip(data, Lz77Config(repDistanceCount = 1))
    }

    @Test
    fun `overlapping match distance one`() {
        roundTrip(ByteArray(1000) { if (it < 3) it.toByte() else 5 })
    }

    @Test
    fun `small window forces wrap around`() {
        val config = Lz77Config(windowBits = 8, lengthBits = 4, minMatch = HASH_LENGTH)
        roundTrip(ByteArray(100_000) { (it % 251).toByte() }, config)
    }

    @Test
    fun `various sizes round trip`() {
        val rnd = Random(99)
        for (size in intArrayOf(0, 1, 2, 3, 4, 5, 16, 17, 255, 256, 257, 1023, 4096, 65_537)) {
            val data = ByteArray(size)
            for (i in 0 until size) {
                data[i] = if (i % 3 == 0) rnd.nextInt(256).toByte() else (i % 7).toByte()
            }
            roundTrip(data)
        }
    }

    @Test
    fun `large mixed data round trips`() {
        val data = ByteArray(200_000) { ((it * 31) % 97).toByte() }
        roundTrip(data)
    }

    @Test
    fun `round trips distances spanning the align boundary`() {
        // Emit matches at deterministically growing gaps so distance-1 sweeps mantissa widths from
        // zero through and well past the range format's DISTANCE_ALIGN_BITS, exercising the
        // align/raw split in both directions. Each repeated marker is separated by an incrementing
        // run of filler bytes.
        val marker = "PALMmarker".toByteArray()
        val output = ByteArrayOutputStream()
        for (gap in 0 until 4_000) {
            output.write(marker)
            repeat(gap % 300) { output.write(('a'.code + (it % 26))) }
        }
        roundTrip(output.toByteArray())
    }

    @Test
    fun `lazy encoder round trips mixed data`() {
        val random = Random(2026)
        val output = ByteArrayOutputStream()
        repeat(100) {
            val noise = ByteArray(random.nextInt(100)).also { random.nextBytes(it) }
            output.write(noise)
            output.write("abcX____bcdeYabcdePALMPALMPALM".toByteArray())
        }
        roundTrip(output.toByteArray())
    }

    // ---- lazy parsing policy --------------------------------------------------------------------

    @Test
    fun `lazy encoder chooses a match longer by two bytes`() {
        val config = Lz77Config()
        val minMatch = config.minMatch
        // The long run planted first is two bytes longer than the run reachable at the current
        // position, so stepping one literal forward buys a strictly better match.
        val longRun = distinctRun(minMatch + 2, first = 40)
        val shortKey = byteArrayOf(LEADING_BYTE.toByte()) + longRun.copyOf(minMatch - 1)
        val data = longRun + SEPARATOR.toByte() + shortKey + SECOND_SEPARATOR.toByte() +
                LEADING_BYTE.toByte() + longRun

        val tokens = lazyTokensOf(data, config)
        val longRunDistance = data.size - longRun.size
        assertEquals(
            listOf("L$LEADING_BYTE", "M${longRun.size},$longRunDistance"),
            tokens.takeLast(2),
            tokens.toString(),
        )
    }

    @Test
    fun `lazy encoder chooses a one-byte-longer match at half the distance`() {
        val config = Lz77Config()
        val minMatch = config.minMatch
        // Both candidates are planted: a match of exactly minMatch bytes far back, and a match one
        // byte longer at half that distance. One byte longer alone would not win, and neither would
        // a distance merely smaller — the tie-break compares binary magnitudes — so this pins both.
        val nearRun = distinctRun(minMatch + 1, first = 40)
        val farKey = byteArrayOf(LEADING_BYTE.toByte()) + nearRun.copyOf(minMatch - 1)
        // The near match repeats nearRun across SECOND_SEPARATOR and LEADING_BYTE.
        val nearDistance = nearRun.size + 2
        // The far match reaches farKey at index 0, so its distance is the index of the tail's
        // LEADING_BYTE, i.e. everything planted before it. Padding brings that to twice
        // nearDistance, one whole binary magnitude further out.
        val padding = ByteArray(2 * nearDistance - farKey.size - nearRun.size - 1) { SEPARATOR.toByte() }
        val data = farKey + padding + nearRun + SECOND_SEPARATOR.toByte() + LEADING_BYTE.toByte() + nearRun

        val tokens = lazyTokensOf(data, config)
        assertEquals(
            listOf("L$LEADING_BYTE", "M${nearRun.size},$nearDistance"),
            tokens.takeLast(2),
            tokens.toString(),
        )
    }

    @Test
    fun `lazy encoder keeps the first match when the closer match is not close enough`() {
        val config = Lz77Config()
        // The whole run matches at the current position; one byte later only its tail does, which
        // is shorter. The look-ahead match is not longer, so the lazy policy keeps the first match.
        val run = distinctRun(config.minMatch + 1)
        val data = run + SEPARATOR.toByte() + run
        val tokens = lazyTokensOf(data, config)
        assertTrue("M${run.size},${run.size + 1}" in tokens, tokens.toString())
    }

    @Test
    fun `reported matches stay within configured bounds`() {
        val config = Lz77Config(lengthBits = 4, minMatch = HASH_LENGTH)
        val consumer = object : Lz77TokenConsumer(config, RepDistances(config.repDistanceCount)) {
            override fun literal(value: Int) = assertTrue(value in 0..255)
            override fun match(length: Int, distance: Int, lastByte: Int) {
                assertTrue(length in config.minMatch..config.maxMatch, "length $length out of range")
                assertTrue(distance in 1..config.maxDistance, "distance $distance out of range")
            }

            override fun finish() {}
        }
        ByteArray(5_000) { 'Z'.code.toByte() }.lz77GreedyEncode(consumer, RepDistances(config.repDistanceCount))
    }

    // ---- dictionary primitives in isolation -----------------------------------------------------

    @Test
    fun `longestMatch is read only and repeatable`() {
        val config = Lz77Config()
        val dictionary = Lz77Dictionary(config)
        val run = distinctRun(config.minMatch)
        val data = run + SEPARATOR.toByte() + run + run
        dictionary.advanceLiterals(data, 0, run.size + 1) // record the run and the separator behind it
        // Repeated queries at the same state return the same match and mutate nothing.
        val first = dictionary.longestMatch(data, run.size + 1)
        assertTrue(first.isMatch)
        val second = dictionary.longestMatch(data, run.size + 1)
        assertEquals(first.length, second.length)
        assertEquals(first.distance, second.distance)
    }

    @Test
    fun `no match before any history is recorded`() {
        val config = Lz77Config()
        val dictionary = Lz77Dictionary(config)
        val run = distinctRun(config.minMatch)
        assertTrue(!dictionary.longestMatch(run + run).isMatch)
    }

    @Test
    fun `advanceLiteral builds searchable history for longestMatch`() {
        val config = Lz77Config()
        val dictionary = Lz77Dictionary(config)
        // Record the run plus a separator, then query with the bytes that follow: the copy of the
        // run must be found, and only up to the separator that ends the recorded copy.
        val run = distinctRun(config.minMatch)
        val data = run + SEPARATOR.toByte() + run + run
        dictionary.advanceLiterals(data, 0, run.size + 1)
        val m = dictionary.longestMatch(data, run.size + 1)
        assertTrue(m.isMatch, "the repeated run should match the recorded one")
        assertEquals(run.size + 1, m.distance) // the recorded run starts one separator further back
        assertEquals(run.size, m.length) // the separator ends the recorded copy
    }

    @Test
    fun `advanceMatch records the bytes it replays`() {
        // A match token carries no bytes of its own, so recording it must index exactly the history
        // its back-reference expands to — indistinguishable from recording those bytes as literals.
        val config = Lz77Config()
        val run = distinctRun(config.minMatch)
        val prefix = run + SEPARATOR.toByte()
        val expanded = prefix + run

        val literals = Lz77Dictionary(config)
        literals.advanceLiterals(expanded)
        val token = Lz77Dictionary(config)
        token.advanceLiterals(prefix)
        token.advanceMatch(run.size, prefix.size)

        assertSameHistory(literals, token, expanded)
    }

    @Test
    fun `advanceMatch replays an overlapping match`() {
        // Distance one with a longer length: every byte is read after the previous one was written,
        // so the last recorded byte repeats, exactly as a decoder expands an RLE match.
        val config = Lz77Config()
        val run = distinctRun(config.minMatch)
        val repeated = 3 * config.minMatch
        val expanded = run + ByteArray(repeated) { run.last() }

        val literals = Lz77Dictionary(config)
        literals.advanceLiterals(expanded)
        val token = Lz77Dictionary(config)
        token.advanceLiterals(run)
        token.advanceMatch(repeated, 1)

        assertSameHistory(literals, token, expanded)
    }

    @Test
    fun `remembered distances are ordered most recent first`() {
        val repDistances = RepDistances(3)
        for (distance in intArrayOf(10, 20, 30)) {
            repDistances.remember(distance)
        }
        assertEquals(0, repDistances.indexOf(30)) // the most recent match
        assertEquals(1, repDistances.indexOf(20))
        assertEquals(2, repDistances.indexOf(10))
        assertEquals(-1, repDistances.indexOf(40)) // never used
        assertEquals(30, repDistances[0])
    }

    @Test
    fun `a repeated distance is promoted rather than duplicated`() {
        val repDistances = RepDistances(3)
        for (distance in intArrayOf(10, 20, 30, 10)) {
            repDistances.remember(distance)
        }
        // 10 moved to the front from the back, so 30 and 20 each slid one position down and
        // nothing was pushed out.
        assertEquals(0, repDistances.indexOf(10))
        assertEquals(1, repDistances.indexOf(30))
        assertEquals(2, repDistances.indexOf(20))
    }

    @Test
    fun `the least recently used distance falls out`() {
        val repDistances = RepDistances(2)
        for (distance in intArrayOf(10, 20, 30)) {
            repDistances.remember(distance)
        }
        assertEquals(0, repDistances.indexOf(30))
        assertEquals(1, repDistances.indexOf(20))
        assertFalse(repDistances.has(10), "the oldest distance should have been pushed out")
    }

    @Test
    fun `has reports membership without the position`() {
        val repDistances = RepDistances(2)
        assertFalse(repDistances.has(10), "nothing is remembered yet")
        repDistances.remember(10)
        repDistances.remember(20)
        assertTrue(repDistances.has(10))
        assertTrue(repDistances.has(20))
        assertFalse(repDistances.has(30), "never used")
    }

    @Test
    fun `no distance is remembered when the list is empty`() {
        val repDistances = RepDistances(0)
        repDistances.remember(10)
        assertEquals(0, repDistances.size)
        assertFalse(repDistances.has(10))
        assertEquals(-1, repDistances.indexOf(10))
    }

    @Test
    fun `advanceMatch rejects a token it cannot replay`() {
        val config = Lz77Config()
        val dictionary = Lz77Dictionary(config)
        assertFailsWith<IllegalArgumentException> { dictionary.advanceMatch(-1, 1) }
        assertFailsWith<IllegalArgumentException> { dictionary.advanceMatch(1, config.maxDistance + 1) }
    }

    @Test
    fun `a negative repDistanceCount is rejected`() {
        assertFailsWith<IllegalArgumentException> { Lz77Config(repDistanceCount = -1) }
    }

    @Test
    fun `an out of range maxChain is rejected`() {
        assertFailsWith<IllegalArgumentException> { Lz77Config(maxChain = 0) }
        assertFailsWith<IllegalArgumentException> { Lz77Config(maxChain = MAX_CHAIN + 1) }
        Lz77Config(maxChain = MAX_CHAIN) // the cap itself stays usable
    }

    @Test
    fun `a repDistanceCount above the sanity cap is rejected`() {
        assertFailsWith<IllegalArgumentException> { Lz77Config(repDistanceCount = MAX_REP_DISTANCE_COUNT + 1) }
        Lz77Config(repDistanceCount = MAX_REP_DISTANCE_COUNT) // the cap itself stays usable
    }

    @Test
    fun `an out of range minMatch is rejected`() {
        assertFailsWith<IllegalArgumentException> { Lz77Config(minMatch = HASH_LENGTH - 1) }
        assertFailsWith<IllegalArgumentException> { Lz77Config(minMatch = MAX_MIN_MATCH + 1) }
        // The cap itself stays usable, and the longest match it admits still fits a positive Int.
        assertTrue(Lz77Config(minMatch = MAX_MIN_MATCH, lengthBits = MAX_LENGTH_BITS).maxMatch > 0)
    }

    @Test
    fun `longestMatch searches an external pattern against recorded history`() {
        // Record history from one array, then query with a completely separate array holding the
        // same run between different neighbors. The dictionary never treats the query array as
        // storage, so the match comes purely from its own history, and the differing neighbors
        // pin the reported length to the shared run.
        val config = Lz77Config()
        val dictionary = Lz77Dictionary(config)
        val shared = distinctRun(config.minMatch + 2)
        val history = byteArrayOf(SEPARATOR.toByte()) + shared + SEPARATOR.toByte()
        dictionary.advanceLiterals(history)

        val query = byteArrayOf(LEADING_BYTE.toByte()) + shared + SECOND_SEPARATOR.toByte()
        val m = dictionary.longestMatch(query, 1) // pattern starts at the shared run
        assertTrue(m.isMatch, "a run present only in the history array must still be found")
        assertEquals(shared.size, m.length) // the differing neighbor ends the match
        // Repeating the query mutates nothing: the dictionary stays queryable.
        assertEquals(m.length, dictionary.longestMatch(query, 1).length)
    }

    @Test
    fun `pattern shorter than minMatch yields no match`() {
        val config = Lz77Config()
        val dictionary = Lz77Dictionary(config)
        val run = distinctRun(config.minMatch)
        val data = run + run
        dictionary.advanceLiterals(data, 0, run.size) // history now holds the whole run
        // One byte fewer than minMatch offered as the pattern: no match regardless of history.
        assertTrue(!dictionary.longestMatch(data, run.size, config.minMatch - 1).isMatch)
    }

    @Test
    fun `maxChain bounds how many chain candidates are inspected`() {
        // Two copies of the same leading run share a hash bucket. The newest copy diverges as soon
        // as the run ends, the oldest continues into a longer tail that the query also holds. The
        // chain is walked newest-first, so only a walk that reaches past the first candidate can
        // find the longer, older match.
        val config = Lz77Config()
        val run = distinctRun(config.minMatch)
        val tail = distinctRun(3, first = 40)
        val query = run + tail
        val history = query + SEPARATOR.toByte() + SEPARATOR.toByte() + run + SECOND_SEPARATOR.toByte()

        val chain1 = Lz77Dictionary(Lz77Config(maxChain = 1))
        chain1.advanceLiterals(history)
        // Only the newest copy is inspected, and it ends where the run does.
        assertEquals(run.size, chain1.longestMatch(query).length)

        val chainMany = Lz77Dictionary(config)
        chainMany.advanceLiterals(history)
        // The walk reaches the older copy, which carries the tail as well.
        assertEquals(query.size, chainMany.longestMatch(query).length)
    }

    @Test
    fun `slot reuse after wrap never yields a bogus reference`() {
        // A tiny ring forced to wrap many times: stale chain links point at slots newer positions
        // have overwritten. Every reported match must still reference the true recorded bytes,
        // which we verify directly against the known stream rather than via a decoder.
        val config = Lz77Config(windowBits = 4, lengthBits = 2, minMatch = HASH_LENGTH)
        val dictionary = Lz77Dictionary(config)
        // A repeat sits one period back, so the period must reach minMatch to be reportable at all
        // and must stay inside the ring to be reachable.
        val period = config.minMatch + 1
        assertTrue(period <= config.maxDistance, "the period must fit the ring")
        val unit = ByteArray(period) { ('a' + it).code.toByte() }
        val stream = ByteArray(64) { unit[it % unit.size] } // wraps the ring several times
        dictionary.advanceLiterals(stream)

        val query = ByteArray(config.maxMatch) { unit[it % unit.size] }
        val m = dictionary.longestMatch(query)
        assertTrue(m.isMatch)
        assertTrue(m.distance in 1..config.maxDistance)
        // The matched bytes must equal the recorded bytes `distance` back: source index in the
        // stream is (bytesRecorded - distance + i), matching Lz77Decoder's copy semantics.
        for (i in 0 until m.length) {
            assertEquals(
                stream[stream.size - m.distance + i], query[i],
                "reported match byte $i does not reference the real history",
            )
        }
    }

    @Test
    fun `reported distance never exceeds maxDistance`() {
        // Highly repetitive data over a tiny window: the greedy driver would love far-back matches,
        // so this stresses that no reported distance exceeds maxDistance = windowSize - 1.
        val config = Lz77Config(windowBits = 8, lengthBits = 4, minMatch = HASH_LENGTH, maxChain = 256)
        val data = ByteArray(50_000) { (it % 3).toByte() }
        val restored = ByteArrayOutputStream()
        val consumer = object : Lz77TokenConsumer(config, RepDistances(config.repDistanceCount)) {
            val decoder = Lz77Decoder(config, restored, RepDistances(config.repDistanceCount))
            override fun literal(value: Int) = decoder.literal(value)
            override fun match(length: Int, distance: Int, lastByte: Int) {
                assertTrue(distance in 1..config.maxDistance, "distance $distance exceeds ${config.maxDistance}")
                decoder.match(length, distance)
            }

            override fun finish() = decoder.finish()
        }
        data.lz77GreedyEncode(consumer, RepDistances(config.repDistanceCount))
        assertContentEquals(data, restored.toByteArray())
    }

    @Test
    fun `smallest valid geometry round trips`() {
        // windowBits 3 -> windowSize 8 and maxDistance 7, lengthBits 1 -> the narrowest length
        // alphabet: a tiny ring that stresses wrap-around and chain breaks hardest.
        val config = Lz77Config(windowBits = 3, lengthBits = 1, minMatch = HASH_LENGTH)
        assertEquals(8, config.windowSize)
        assertTrue(config.maxMatch > config.minMatch, "a geometry must reach past minMatch")
        assertEquals(7, config.maxDistance)

        roundTrip(ByteArray(5_000) { 'A'.code.toByte() }, config)
        roundTrip(ByteArray(5_000) { (it % 7).toByte() }, config)
        val rnd = Random(3)
        val noise = ByteArray(5_000).also { rnd.nextBytes(it) }
        roundTrip(noise, config)
    }

    @Test
    fun `lengths on both sides of the short range boundary round trip`() {
        // The range format splits a length field at 2^SHORT_LENGTH_BITS. Plant runs whose lengths
        // straddle that boundary, so both the short and the long tree are exercised together with
        // the range flag between them.
        val config = Lz77Config()
        val shortRange = 1 shl SHORT_LENGTH_BITS
        val lengths = listOf(
            config.minMatch,
            config.minMatch + shortRange - 1, // last short value
            config.minMatch + shortRange, // first long value
            config.minMatch + shortRange + 1,
            config.maxMatch,
        )
        val data = ByteArrayOutputStream()
        for ((index, length) in lengths.withIndex()) {
            val run = distinctRun(length, first = 1 + index)
            data.write(run)
            data.write(SEPARATOR)
            data.write(run) // planted copy: a match of exactly `length` bytes
            data.write(SECOND_SEPARATOR)
        }
        roundTrip(data.toByteArray(), config)
    }

    @Test
    fun `a length alphabet inside the short range round trips`() {
        // lengthBits below SHORT_LENGTH_BITS narrows the short range to the whole alphabet, so the
        // short tree and the long one are coded at the same depth.
        val config = Lz77Config(windowBits = 10, lengthBits = 2, minMatch = HASH_LENGTH)
        assertTrue(config.lengthBits < SHORT_LENGTH_BITS)
        roundTrip(ByteArray(5_000) { 'Q'.code.toByte() }, config)
        roundTrip(ByteArray(5_000) { (it % 5).toByte() }, config)
        val rnd = Random(11)
        roundTrip(ByteArray(5_000).also { rnd.nextBytes(it) }, config)
    }

    @Test
    fun `a length alphabet exactly filling the short range round trips`() {
        // lengthBits equal to SHORT_LENGTH_BITS is the boundary case of the previous test: the last
        // width at which the short range still spans the whole geometry.
        val config = Lz77Config(windowBits = 12, lengthBits = SHORT_LENGTH_BITS, minMatch = HASH_LENGTH)
        roundTrip(ByteArray(8_000) { (it % 11).toByte() }, config)
        roundTrip("repeat repeat repeat repeat repeat".repeat(60).toByteArray(), config)
    }

    @Test
    fun `a large minMatch round trips through the range length split`() {
        // The length field is length - minMatch, so a large minMatch shifts which real lengths fall
        // into the short range; the previous-length context must stay in step on both sides.
        val config = Lz77Config(windowBits = 14, lengthBits = 6, minMatch = 16)
        val unit = distinctRun(40)
        val data = ByteArrayOutputStream()
        repeat(200) {
            data.write(unit)
            data.write(SEPARATOR)
        }
        roundTrip(data.toByteArray(), config)
    }

    @Test
    fun `a decoded length always fits the configured alphabet`() {
        // The range format codes a long length through a lengthBits-deep tree, so the tree spans
        // exactly the legal alphabet and no stream can name a length past maxMatch. Drive every
        // reachable code point of the long tree through the reader and check the plain length.
        for (lengthBits in listOf(1, SHORT_LENGTH_BITS, SHORT_LENGTH_BITS + 1, DEFAULT_LENGTH_BITS)) {
            val config = Lz77Config(windowBits = 12, lengthBits = lengthBits, minMatch = HASH_LENGTH)
            val repDistances = RepDistances(config.repDistanceCount)
            val packed = ByteArrayOutputStream()
            val lengths = (config.minMatch..config.maxMatch).toList()
            val consumer = RangeLz77TokenConsumer(config, packed, repDistances)
            for (length in lengths) {
                consumer.match(length, 1, LAST_BYTE_UNDER_TEST)
                repDistances.remember(1)
            }
            consumer.finish()

            val readerRepDistances = RepDistances(config.repDistanceCount)
            val reader = RangeLz77TokenReader(
                config,
                ByteArrayInputStream(packed.toByteArray()),
                readerRepDistances,
            )
            for (expected in lengths) {
                assertTrue(reader.readIsMatch(), "a match token should be read back")
                val length = reader.readLength()
                assertEquals(expected, length, "lengthBits $lengthBits length mismatch")
                assertTrue(
                    length in config.minMatch..config.maxMatch,
                    "decoded length $length outside ${config.minMatch}..${config.maxMatch}",
                )
                readerRepDistances.remember(reader.readDistance())
            }
        }
    }

    @Test
    fun `every previous-length group is reachable`() {
        // The previous match's length group picks the length context on both sides. A group no
        // length maps to would be dead, and a length mapping past the count would corrupt the
        // index. A narrow alphabet reaches only the lowest groups, so reachability is asserted
        // against the groups the geometry can actually produce.
        for (lengthBits in listOf(1, SHORT_LENGTH_BITS, SHORT_LENGTH_BITS + 1, DEFAULT_LENGTH_BITS)) {
            val config = Lz77Config(windowBits = 12, lengthBits = lengthBits, minMatch = HASH_LENGTH)
            val groups = (0..config.maxMatch - config.minMatch).map { lengthPrevGroup(it) }
            assertTrue(
                groups.all { it in 0 until LENGTH_PREV_GROUPS },
                "lengthBits $lengthBits maps a length outside the group count",
            )
            assertEquals(
                (0..groups.max()).toSet(),
                groups.toSet(),
                "lengthBits $lengthBits leaves a group unreachable below its widest one",
            )
        }
        // The widest alphabet must reach every group, or a group would be dead in every geometry.
        val widest = (0 until (1 shl MAX_LENGTH_BITS)).map { lengthPrevGroup(it) }.toSet()
        assertEquals((0 until LENGTH_PREV_GROUPS).toSet(), widest, "a group is unreachable")
    }

    @Test
    fun `the narrowest length alphabet keeps every match inside the geometry`() {
        // lengthBits 1 is the narrowest length alphabet, so a long run must be split into many
        // short matches, each no longer than maxMatch and each within maxDistance.
        val config = Lz77Config(windowBits = 10, lengthBits = 1, minMatch = HASH_LENGTH)
        assertEquals(
            config.minMatch + shortLengthCount(config.lengthBits) + (1 shl config.lengthBits) - 1,
            config.maxMatch,
            "maxMatch must span the short range and the long one together",
        )
        val consumer = object : Lz77TokenConsumer(config, RepDistances(config.repDistanceCount)) {
            override fun literal(value: Int) = assertTrue(value in 0..255)
            override fun match(length: Int, distance: Int, lastByte: Int) {
                assertTrue(length in config.minMatch..config.maxMatch, "length $length out of range")
                assertTrue(distance in 1..config.maxDistance, "distance $distance out of range")
            }

            override fun finish() {}
        }
        ByteArray(5_000) { 'Z'.code.toByte() }.lz77GreedyEncode(consumer, RepDistances(config.repDistanceCount))
        // And it still reconstructs losslessly with the same tiny length cap.
        roundTrip(ByteArray(5_000) { 'Z'.code.toByte() }, config)
    }
}
