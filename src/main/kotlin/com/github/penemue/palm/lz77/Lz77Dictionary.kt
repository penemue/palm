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

import com.github.penemue.palm.util.fibonacciHash
import com.github.penemue.palm.util.unsignedInt

/**
 * Number of leading bytes fed into the match hash, so [Lz77Config.minMatch] cannot fall below it.
 */
const val HASH_LENGTH = 5

/**
 * A fast, stateful **[LZ77](https://en.wikipedia.org/wiki/LZ77_and_LZ78) dictionary** built around a
 * *cyclic byte buffer*.
 *
 * This is the low-level engine of an LZ77 pipeline: it indexes a sliding window of history and
 * answers the single question *"what is the longest run inside my history that matches a pattern
 * you hand me?"*. Tokens, literals, encoding and the parsing strategy belong to a caller built on
 * top of it.
 *
 * ### The primitives
 *  - [longestMatch] searches the indexed history for the longest run matching an **external**
 *    pattern; read-only, it mutates nothing.
 *  - [advanceLiteral] and [advanceMatch] record an emitted token as history: they copy its bytes
 *    into the window and index each into the hash chains, so they become searchable (reachable up
 *    to [Lz77Config.maxDistance] bytes back) by later queries.
 *
 * A driver uses them in the natural order: call [longestMatch] on the upcoming bytes, decide what
 * to emit — one literal or a match — then record exactly that token. The match is found *before*
 * anything is recorded, and history grows by exactly what was consumed. Since a token carries its
 * own bytes (a literal directly, a match as a back-reference into this very window), the driver
 * never hands the source array back.
 *
 * ### No overlap: matches stay inside history
 * A match never reaches the current position, so its length is bounded by its distance (a candidate
 * `d` bytes back yields at most `d` bytes). Overlapping, RLE-style runs (`distance < length`, e.g. a
 * repeated byte) are therefore **not** produced here; a caller that wants them extends the returned
 * match along its own byte stream, where a contiguous output actually exists.
 *
 * ### Cyclic buffer
 * History lives in [window], whose first [Lz77Config.windowSize] bytes are the window slots, a
 * power of two. The whole window is history; the furthest a match may reach back is
 * [Lz77Config.maxDistance] `= windowSize - 1`. Position is tracked purely as a window slot
 * ([currentSlot]), with no ever-growing absolute counter, so a single instance handles a stream of
 * **any** length in bounded memory.
 *
 * Behind those slots [window] carries a mirror of its own first [Lz77Config.maxMatch] bytes, kept in
 * sync as history is recorded, so a run wrapping past the last slot is still a *flat* range of
 * bytes: reads may run off the window end for as long as a match may last.
 *
 * ### Hash chains
 * [head] maps a [HASH_LENGTH]-byte hash to the most recent history position that produced it, and
 * [prev] links each slot to the previous position with the same hash. Walking [prev] from [head]
 * visits candidates newest-first.
 *
 * Both arrays hold *tagged links*: the window slot in the low bits, the owning bucket index, as far
 * as it fits, in the bits above it. Since the window slides, a slot is eventually reused by an
 * unrelated position, which rewrites its link with the new owner's tag; a walk that reaches such a
 * slot sees a foreign tag and stops. One comparison therefore terminates the walk on a stale link,
 * on a chain end and on an empty bucket alike. The search is additionally bounded by
 * [Lz77Config.maxChain].
 *
 * ### Thread-safety
 * A single instance is **not** thread-safe and, being stateful, must not be shared between streams.
 * Create one instance per logical stream.
 *
 * @param config the parameters controlling window size, match limits and search effort.
 */
class Lz77Dictionary(config: Lz77Config) {

    private val minMatch = config.minMatch
    private val maxMatch = config.maxMatch
    private val maxChain = config.maxChain
    private val windowBits = config.windowBits

    // The window sizes the hash table as well, so windowMask doubles as the hash-bucket mask.
    private val windowSize = config.windowSize
    private val windowMask = windowSize - 1

    // The window slots are followed by a mirror of the first maxMatch of them, kept in sync byte for byte by record.
    // The mirror makes a wrapping run readable as a flat range: the longest readable run, maxMatch bytes from the
    // last slot, ends exactly at the mirror's last byte.
    private val window = ByteArray(windowSize + maxMatch)

    // Selects the tag bits of a link, i.e. everything above the windowBits slot bits.
    private val tagMask = windowMask.inv()

    // Most recent link of each hash bucket. There is no "empty" sentinel: a zero link is accepted only by bucket 0,
    // which therefore starts on slot 0 until it is written.
    private val head = IntArray(windowSize)

    // For each window slot, a tagged link to the previous position sharing its hash bucket: the low windowBits bits
    // hold that position's window slot, and the bits above them hold as much of the owning bucket index as fits in
    // the remaining width. A link is followed only while its tag matches the bucket being walked; once a slot is
    // reused by a newer position, its link is rewritten with that position's bucket tag, so a walk that steps onto a
    // reused slot sees a foreign tag and stops. Entries are never cleared. A stale link whose tag survives the test
    // merely costs one extra candidate compare that fails on its own bytes; correctness never depends on the tag.
    // A slot is pushed onto the bucket of the run beginning there, so the tag it displaces into this array also
    // fingerprints that run: prev[s] carries the bucket of the HASH_LENGTH bytes starting at slot s.
    private val prev = IntArray(windowSize)

    // The newest history byte sits just behind currentSlot, and the next byte recorded lands on it.
    private var currentSlot = 0

    /**
     * Returns the longest run inside this window's indexed history that matches the given external
     * [pattern], starting at [offset] and considering up to [length] bytes.
     *
     * The pattern is supplied by the caller rather than taken from this window, so the bytes being
     * matched need never have been recorded here. The search is read-only: it inspects the hash
     * chains but mutates no state, so it may be called repeatedly.
     *
     * A reported match never reaches the current position, so its length is at most its distance
     * (see the class documentation on overlap); a caller wanting an RLE-style overlapping run
     * extends the match along its own byte stream. The reported length is also capped at
     * [Lz77Config.maxMatch].
     *
     * The first [HASH_LENGTH] bytes of the pattern select the hash chain to walk, so a match of
     * fewer than [HASH_LENGTH] bytes is never found; [length] below [Lz77Config.minMatch] yields no
     * match at all.
     *
     * @param pattern bytes to match against this window's history.
     * @param offset index of the first pattern byte, defaulting to `0`.
     * @param length number of pattern bytes to consider from [offset], defaulting to the rest of
     * [pattern]; the reported length never exceeds `min(length, maxMatch)`.
     * @return the best match; [Match.isMatch] is `false` when no run of at least
     * [Lz77Config.minMatch] bytes exists.
     */
    fun longestMatch(pattern: ByteArray, offset: Int = 0, length: Int = pattern.size - offset): Match =
        Match(findMatch(pattern, offset, length))

    /**
     * Records a single emitted literal as history, so it becomes searchable by subsequent
     * [longestMatch] queries.
     *
     * @param byte the literal just emitted.
     */
    fun advanceLiteral(byte: Byte) = record(byte)

    /**
     * Records an emitted match as history: replays [length] bytes starting [distance] bytes back
     * and indexes each of them, so they become searchable by subsequent [longestMatch] queries.
     * The bytes are read out of this window, so the caller never hands back the array the match was
     * found in.
     *
     * Each byte is read after the preceding one has been written, so an overlapping, RLE-style
     * match ([length] greater than [distance]) replays the repeating run the same way a decoder
     * does.
     *
     * @param length number of bytes the match covers.
     * @param distance how far back the match starts, in `1..`[Lz77Config.maxDistance].
     * @throws IllegalArgumentException if [length] is negative or [distance] is out of range.
     */
    fun advanceMatch(length: Int, distance: Int) {
        require(length >= 0) { "match length $length is negative" }
        require(distance in 1..windowMask) { "match distance $distance is out of range 1..$windowMask" }
        repeat(length) {
            record(window[(currentSlot - distance) and windowMask])
        }
    }

    /**
     * Records one byte as history: writes it to [currentSlot], mirroring it into the window tail
     * when that slot is shadowed there, then indexes a position and moves on to the next slot.
     *
     * The position indexed is not the one just written: after writing a byte, the [HASH_LENGTH]-byte
     * run *ending* on that slot is complete, so the position `HASH_LENGTH - 1` slots back is indexed
     * by that trailing run. Recording therefore needs no look-ahead and works one byte at a time,
     * and it indexes from the very first byte with no warm-up: the window is zero-filled, so the
     * trailing run of the earliest positions is simply `(0, ..., 0, b)`. Encoder and decoder windows
     * are both zero-filled and updated in lock-step, so a match found against any slot — real data
     * or an initial zero — is reproduced faithfully.
     */
    private fun record(byte: Byte) {
        window[currentSlot] = byte
        // Keep the mirrored tail identical to the slots it shadows, so a compare that runs off
        // the window end reads the wrapped-around bytes.
        if (currentSlot < maxMatch) {
            window[currentSlot + windowSize] = byte
        }
        // Index the position where the run ending at currentSlot starts: HASH_LENGTH - 1 slots
        // back, keyed by that run (the HASH_LENGTH bytes that begin there).
        val startSlot = (currentSlot - HASH_LENGTH + 1) and windowMask
        val h = window.hashAt(startSlot)
        // Push the slot onto bucket h's chain, tagging both links with h, so a later walk
        // recognizes this link as still belonging to h. Rewriting prev[startSlot] here invalidates
        // the link the slot carried for its previous owner.
        prev[startSlot] = head[h]
        head[h] = (h shl windowBits) or startSlot
        currentSlot = (currentSlot + 1) and windowMask
    }

    /**
     * Finds the longest run inside history matching `pattern[offset until offset + length]`, the
     * unpacked engine behind [longestMatch].
     *
     * The walk ends on the first link whose tag is foreign (see [prev]), and is bounded by
     * [maxChain]; it also stops early once a match of the maximum useful length is found or once a
     * candidate reveals a periodic run. Distances need no upper check: window arithmetic keeps them
     * within [Lz77Config.maxDistance] by construction.
     *
     * A candidate `d` bytes back is compared for at most `d` bytes (`candidateLen`), so the compare
     * never reaches [currentSlot]; that keeps a match strictly inside history — no overlap into the
     * pattern. A candidate holding fewer than [HASH_LENGTH] bytes is skipped outright. Before
     * comparing, a candidate must pass two rejection probes: the link of the slot `probeOffset`
     * bytes past it must fingerprint the gram the pattern ends the shortest improving run with (see
     * [prev]), and its byte at the best length so far must match the pattern there. While that run
     * is the key itself — no match found yet under a [minMatch] of [HASH_LENGTH] — the gram probe
     * would test the very bucket being walked, so it is skipped.
     *
     * The comparison starts at [HASH_LENGTH], taking on trust that a candidate drawn from this
     * bucket carries the pattern's key. That key is then proven byte for byte before a candidate is
     * accepted, so a bucket collision never reaches the result.
     *
     * @param pattern bytes to match against this window's history.
     * @param offset index of the first pattern byte.
     * @param length number of pattern bytes to consider from [offset]; the reported length never
     * exceeds `min(length, maxMatch)`.
     * @return the match packed to avoid allocating on the hot path — length in the high 32 bits,
     * distance in the low 32 bits — or [NO_MATCH] when no run of at least [minMatch] bytes exists.
     */
    private fun findMatch(pattern: ByteArray, offset: Int, length: Int): Long {
        if (length < minMatch) return NO_MATCH

        val maxLen = minOf(length, maxMatch)
        val bucket = pattern.hashAt(offset)
        val tag = bucket shl windowBits
        var link = head[bucket]
        var bestLen = 0
        var bestDist = 0
        var nextByte = pattern[offset]
        // The shortest run still worth reporting spans the pattern up to probeOffset + HASH_LENGTH, so a candidate
        // able to beat bestLen starts the pattern's gram at probeOffset that many slots past its own. Both fields
        // move up as bestLen grows.
        var probeOffset = minMatch - HASH_LENGTH
        var probeTag = if (probeOffset == 0) tag else pattern.hashAt(offset + probeOffset) shl windowBits
        var candidatesLeft = maxChain

        // A link belongs to this chain only while it still carries this bucket's tag; a reused slot
        // or an empty bucket carries a foreign one, which ends the walk.
        while (link and tagMask == tag && --candidatesLeft >= 0) {
            val candidate = link and windowMask
            // The next link depends on nothing the probe or the compare produce, and a walk writes
            // nothing, so loading it here overlaps its latency with them.
            link = prev[candidate]
            // Fast rejection: only compare fully when this candidate can beat the best length. The gram test alone
            // settles all but a few candidates, so the byte read lands on a window slot that is worth touching. A
            // slot whose link is still zero has never been indexed and fingerprints nothing, so it must pass. At
            // probeOffset 0 the gram tested is the candidate's own, which the walk condition already settled, so
            // the load is skipped rather than spent on a tautology.
            val probeMatches = probeOffset == 0 ||
                    prev[(candidate + probeOffset) and windowMask].let { it == 0 || it and tagMask == probeTag }
            if (probeMatches && window[candidate + bestLen] == nextByte) {
                val dist = (currentSlot - candidate) and windowMask
                // A candidate d bytes back has only d history bytes ahead of it before reaching the
                // current position, so its match cannot exceed its own distance: no overlap.
                val candidateLen = minOf(dist, maxLen)
                // Closer than a key, so there is nothing the comparison below could stand on.
                if (candidateLen < HASH_LENGTH) continue
                val len = pattern.matchLength(
                    offset = offset,
                    startLen = HASH_LENGTH,
                    maxLen = candidateLen,
                    candidate = candidate,
                )
                if (len > bestLen) {
                    if (pattern.matchLength(
                            offset = offset,
                            startLen = 0,
                            maxLen = HASH_LENGTH,
                            candidate = candidate,
                        ) < HASH_LENGTH
                    ) continue
                    bestLen = len
                    bestDist = dist
                    // Nothing longer can be reported.
                    if (len == maxLen) {
                        break
                    }
                    // A candidate matching every byte it had, this deep into the chain, marks a
                    // periodic run: later candidates can only match as long, and farther away.
                    if (len == dist && maxChain - candidatesLeft > HASH_LENGTH) {
                        break
                    }
                    nextByte = pattern[offset + len]
                    probeOffset = len + 1 - HASH_LENGTH
                    probeTag = pattern.hashAt(offset + probeOffset) shl windowBits
                }
            }
        }

        return if (bestLen >= minMatch) (bestLen.toLong() shl Int.SIZE_BITS) or bestDist.toLong() else NO_MATCH
    }

    /**
     * Hashes the [HASH_LENGTH] key bytes beginning at [offset] into a bucket. The receiver is
     * either this window or a pattern being matched: a pattern must be hashed by the very same code
     * as history, so that it lands in the bucket its bytes would occupy as history.
     *
     * @param offset index of the first key byte; the caller guarantees at least [HASH_LENGTH] bytes
     * are readable from here.
     * @return a value in `0 until windowSize`.
     */
    private fun ByteArray.hashAt(offset: Int): Int {
        var key = 0L
        for (i in 0 until HASH_LENGTH) {
            key = (key shl Byte.SIZE_BITS) or this[offset + i].unsignedInt.toLong()
        }
        return key.fibonacciHash(windowBits)
    }

    /**
     * Compares the receiver's bytes from [offset] against history from [candidate], skipping the
     * first [startLen] of them and stopping at [maxLen] or at the first difference.
     *
     * @return the length reached, so [maxLen] when every byte compared matched.
     */
    private fun ByteArray.matchLength(offset: Int, startLen: Int, maxLen: Int, candidate: Int): Int {
        var i = startLen
        while (i < maxLen && window[candidate + i] == this[offset + i]) {
            i++
        }
        return i
    }

    private companion object {
        /**
         * Packed [findMatch] result meaning "no usable match": zero length, zero distance.
         */
        const val NO_MATCH = 0L
    }

    /**
     * Result of a [longestMatch] query: a back-reference of [length] bytes starting [distance]
     * bytes before the current position.
     *
     * The two fields are packed into a single [Long] so that [longestMatch] allocates nothing on
     * the hot path. When [isMatch] is `false` — i.e. no run of at least [Lz77Config.minMatch] bytes
     * was found — both [length] and [distance] are `0`.
     */
    @JvmInline
    value class Match internal constructor(private val packed: Long) {
        /**
         * Length of the match in bytes, in `minMatch..maxMatch`.
         */
        val length: Int get() = (packed ushr 32).toInt()

        /**
         * Distance back to the start of the match, in `1..maxDistance`. Always at least [length],
         * since a reported match never overlaps into the current position.
         */
        val distance: Int get() = packed.toInt()

        /**
         * Whether the query found a run at all.
         */
        val isMatch: Boolean get() = packed != 0L
    }
}
