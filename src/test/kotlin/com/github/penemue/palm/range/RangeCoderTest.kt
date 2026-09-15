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
package com.github.penemue.palm.range

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the adaptive binary range coder ([RangeEncoder] / [RangeDecoder] / [BitModel]) in
 * isolation from any token format: carry propagation, renormalization, raw-bit coding and
 * end-of-stream handling.
 */
class RangeCoderTest {

    // ---- Scenario 1: single-context adaptive-bit round trips -------------------------------------

    @Test
    fun `all zero bits round trip through a single context`() {
        val bits = List(500) { 0 }
        assertEquals(bits, decodeBits(encodeBits(bits), bits.size))
    }

    @Test
    fun `all one bits round trip through a single context`() {
        val bits = List(500) { 1 }
        assertEquals(bits, decodeBits(encodeBits(bits), bits.size))
    }

    @Test
    fun `alternating bits round trip through a single context`() {
        val bits = List(500) { it and 1 }
        assertEquals(bits, decodeBits(encodeBits(bits), bits.size))
    }

    @Test
    fun `pseudo random bit sequence round trips through a single context`() {
        val random = Random(2026)
        val bits = List(4000) { random.nextInt(2) }
        assertEquals(bits, decodeBits(encodeBits(bits), bits.size))
    }

    // ---- Scenario 2: multi-context round trip ----------------------------------------------------

    @Test
    fun `bits interleaved across many contexts stay in lock step`() {
        val random = Random(2026)
        val bits = List(6000) { random.nextInt(2) }

        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        val encodeModel = BitModel(256)
        var encodeContext = 0
        for (bit in bits) {
            encoder.encodeBit(encodeModel, encodeContext, bit)
            encodeContext = ((encodeContext shl 1) or bit) and 0xFF
        }
        encoder.finish()

        val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
        val decodeModel = BitModel(256)
        var decodeContext = 0
        val decoded = ArrayList<Int>(bits.size)
        repeat(bits.size) {
            val bit = decoder.decodeBit(decodeModel, decodeContext)
            decoded.add(bit)
            decodeContext = ((decodeContext shl 1) or bit) and 0xFF
        }

        assertEquals(bits, decoded)
    }

    // ---- Scenario 3: raw-bit round trips ---------------------------------------------------------

    @Test
    fun `raw bits round trip across every width and boundary value`() {
        val random = Random(2026)
        val cases = ArrayList<Pair<Int, Int>>()
        for (width in 0..32) {
            cases += 0 to width
            cases += 1 to width
            cases += allOnes(width) to width
            cases += Int.MAX_VALUE to width
            cases += Int.MIN_VALUE to width
            cases += random.nextInt() to width
        }

        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        cases.forEach { (value, width) -> encoder.encodeRawBits(value, width) }
        encoder.finish()

        val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
        cases.forEach { (value, width) ->
            assertEquals(mask(value, width), decoder.decodeRawBits(width), "width=$width value=$value")
        }
    }

    @Test
    fun `raw bit count of zero writes and reads nothing`() {
        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        encoder.encodeRawBits(0x1234, 0)
        encoder.finish()
        // Only the five flush bytes are produced when nothing but a zero-width field is coded.
        assertEquals(5, output.size())

        val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
        assertEquals(0, decoder.decodeRawBits(0))
    }

    // ---- Scenario 3b: adaptive bit-tree round trips ----------------------------------------------

    @Test
    fun `bit tree round trips every symbol of a small alphabet`() {
        val bitCount = 4
        val symbols = (0 until (1 shl bitCount)).toList()

        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        val encodeModel = BitModel(1 shl bitCount)
        symbols.forEach { encoder.encodeBitTree(encodeModel, 0, it, bitCount) }
        encoder.finish()

        val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
        val decodeModel = BitModel(1 shl bitCount)
        val decoded = symbols.map { decoder.decodeBitTree(decodeModel, 0, bitCount) }

        assertEquals(symbols, decoded)
    }

    @Test
    fun `bit tree round trips a pseudo random symbol stream across widths`() {
        val random = Random(2026)
        for (bitCount in 1..16) {
            val symbols = List(2000) { random.nextInt(1 shl bitCount) }

            val output = ByteArrayOutputStream()
            val encoder = RangeEncoder(output)
            val encodeModel = BitModel(1 shl bitCount)
            symbols.forEach { encoder.encodeBitTree(encodeModel, 0, it, bitCount) }
            encoder.finish()

            val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
            val decodeModel = BitModel(1 shl bitCount)
            val decoded = symbols.map { decoder.decodeBitTree(decodeModel, 0, bitCount) }

            assertEquals(symbols, decoded, "bitCount=$bitCount")
        }
    }

    @Test
    fun `bit trees sharing one model but different bases stay independent`() {
        val random = Random(2026)
        val bitCount = 8
        val treeSize = 1 shl bitCount
        val bases = intArrayOf(0, treeSize, 2 * treeSize)
        // Each symbol is coded into one of three trees packed into a single model array.
        val plan = List(3000) { random.nextInt(bases.size) to random.nextInt(treeSize) }

        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        val encodeModel = BitModel(bases.size * treeSize)
        plan.forEach { (tree, symbol) -> encoder.encodeBitTree(encodeModel, bases[tree], symbol, bitCount) }
        encoder.finish()

        val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
        val decodeModel = BitModel(bases.size * treeSize)
        val decoded = plan.map { (tree, _) -> decoder.decodeBitTree(decodeModel, bases[tree], bitCount) }

        assertEquals(plan.map { it.second }, decoded)
    }

    @Test
    fun `bit tree of a skewed alphabet compresses below its flat bit width`() {
        val random = Random(2026)
        val bitCount = 8
        // 90% of symbols are 0, the rest uniform: entropy is far below eight bits per symbol.
        val symbols = List(20_000) { if (random.nextInt(10) == 0) random.nextInt(1 shl bitCount) else 0 }

        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        val model = BitModel(1 shl bitCount)
        symbols.forEach { encoder.encodeBitTree(model, 0, it, bitCount) }
        encoder.finish()

        val flatBytes = symbols.size * bitCount / 8
        assertTrue(
            output.size() < flatBytes / 2,
            "expected the skewed stream to compress well below ${flatBytes / 2} bytes, got ${output.size()}",
        )

        val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
        val decodeModel = BitModel(1 shl bitCount)
        val decoded = symbols.map { decoder.decodeBitTree(decodeModel, 0, bitCount) }
        assertEquals(symbols, decoded)
    }

    // ---- Scenario 4: mixed adaptive-bit and raw-bit stream ---------------------------------------

    @Test
    fun `interleaved adaptive and raw bits compose in a recorded order`() {
        val operations = listOf(
            BitOp(0, 1),
            RawOp(0b1011, 4),
            BitOp(1, 0),
            BitOp(2, 1),
            RawOp(Int.MAX_VALUE, 31),
            BitOp(0, 0),
            RawOp(0, 12),
            RawOp(-1, 16),
            BitOp(3, 1),
            RawOp(0xCAFE, 16),
        )

        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        val encodeModel = BitModel(4)
        for (operation in operations) {
            when (operation) {
                is BitOp -> encoder.encodeBit(encodeModel, operation.index, operation.bit)
                is RawOp -> encoder.encodeRawBits(operation.value, operation.count)
            }
        }
        encoder.finish()

        val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
        val decodeModel = BitModel(4)
        for (operation in operations) {
            when (operation) {
                is BitOp -> assertEquals(operation.bit, decoder.decodeBit(decodeModel, operation.index))
                is RawOp ->
                    assertEquals(mask(operation.value, operation.count), decoder.decodeRawBits(operation.count))
            }
        }
    }

    // ---- Scenario 5: carry / renormalization stress ----------------------------------------------

    @Test
    fun `large mixed workload round trips byte for byte`() {
        val random = Random(2026)
        val operations = ArrayList<Op>(40_000)
        var context = 0
        repeat(40_000) {
            if (random.nextInt(3) == 0) {
                val count = random.nextInt(33)
                operations += RawOp(random.nextInt(), count)
            } else {
                // Bias the bit slightly so probabilities drift and drive plenty of renormalization.
                val bit = if (random.nextInt(4) == 0) 1 else 0
                operations += BitOp(context, bit)
                context = ((context shl 1) or bit) and 0xFF
            }
        }

        val encoded = encodeOperations(operations, 256)
        replayAndAssert(operations, encoded, 256)
    }

    @Test
    fun `long runs of one bits stress carry propagation`() {
        // Raw all-ones fields keep low at 0xFF...FF for long stretches, exercising the cached
        // 0xFF run and the late carry in shiftLow.
        val operations = ArrayList<Op>()
        repeat(2000) { operations += RawOp(-1, 32) }
        repeat(2000) { operations += RawOp(0xFFFF, 16) }

        val encoded = encodeOperations(operations, 1)
        replayAndAssert(operations, encoded, 1)
    }

    // ---- Scenario 6: adaptation compresses, raw bits do not --------------------------------------

    @Test
    fun `heavily skewed bits compress far below one eighth of their count`() {
        val n = 20_000
        val random = Random(2026)
        val bits = List(n) { if (random.nextInt(100) < 99) 0 else 1 }

        val bytes = encodeBits(bits).size
        assertEquals(bits, decodeBits(encodeBits(bits), bits.size))
        assertTrue(bytes < n / 16, "skewed stream should be far below ${n / 16} bytes but was $bytes")
    }

    @Test
    fun `equiprobable raw bits produce about one eighth of their count`() {
        val n = 8192
        val random = Random(2026)
        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        repeat(n / 32) { encoder.encodeRawBits(random.nextInt(), 32) }
        encoder.finish()

        val bytes = output.size()
        assertTrue(abs(bytes - n / 8) <= 16, "raw bits should be about ${n / 8} bytes but were $bytes")
    }

    // ---- Scenario 7: finish does not close the output and writes incrementally --------------------

    @Test
    fun `finish flushes without closing the output and the encoder writes incrementally`() {
        val random = Random(2026)
        val stream = TrackingOutputStream()
        val encoder = RangeEncoder(stream)
        val model = BitModel(1)
        repeat(4000) { encoder.encodeBit(model, 0, random.nextInt(2)) }

        // Renormalization must have shipped bytes long before finish is called.
        assertTrue(stream.size() > 0, "encoder should write bytes incrementally during encoding")

        encoder.finish()
        assertFalse(stream.closed, "finish must not close the caller's output stream")
    }

    // ---- Scenario 8: decoder does not close its input; truncation throws --------------------------

    @Test
    fun `decoder does not close its input`() {
        val bits = List(1000) { it and 1 }
        val encoded = encodeBits(bits)
        val input = TrackingInputStream(encoded)
        val decoder = RangeDecoder(input)
        repeat(bits.size) { decoder.decodeBit(BitModel(1), 0) }
        assertFalse(input.closed, "decoder must not close the caller's input stream")
    }

    @Test
    fun `truncated stream throws while decoding`() {
        val random = Random(2026)
        val bits = List(2000) { random.nextInt(2) }
        val encoded = encodeBits(bits)
        // Keep just enough bytes for the constructor to prime, but far too few for the payload.
        val truncated = encoded.copyOf(6)

        val decoder = RangeDecoder(ByteArrayInputStream(truncated))
        val model = BitModel(1)
        assertFailsWith<IOException> {
            repeat(bits.size) { decoder.decodeBit(model, 0) }
        }
    }

    @Test
    fun `empty input to the constructor throws`() {
        assertFailsWith<IOException> { RangeDecoder(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test
    fun `too short input to the constructor throws`() {
        assertFailsWith<IOException> { RangeDecoder(ByteArrayInputStream(byteArrayOf(0, 0, 0))) }
    }

    // ---- Scenario 9: empty payload ---------------------------------------------------------------

    @Test
    fun `empty payload flushes five bytes and decodes as a no op`() {
        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        encoder.finish()
        assertEquals(5, output.size())

        // Constructing a decoder over the flush bytes and decoding nothing must not throw.
        RangeDecoder(ByteArrayInputStream(output.toByteArray()))
    }

    // ---- Scenario 10: determinism and independence -----------------------------------------------

    @Test
    fun `identical input encodes to identical bytes`() {
        val random = Random(2026)
        val bits = List(3000) { random.nextInt(2) }
        assertContentEquals(encodeBits(bits), encodeBits(bits))
    }

    @Test
    fun `two encoders do not share state`() {
        val first = List(1000) { 0 }
        val second = List(1000) { 1 }
        // Each encoder owns its own model, so the two streams cannot influence each other.
        val firstBytes = encodeBits(first)
        val secondBytes = encodeBits(second)
        assertEquals(first, decodeBits(firstBytes, first.size))
        assertEquals(second, decodeBits(secondBytes, second.size))
        assertFalse(firstBytes.contentEquals(secondBytes))
    }

    // ---- Scenario 11: BitModel construction ------------------------------------------------------

    @Test
    fun `a fresh model honors its size and starts every entry at one half`() {
        val half = PROBABILITY_TOTAL / 2
        for (size in intArrayOf(0, 1, 3, 256, 1000)) {
            val model = BitModel(size)
            assertEquals(size, model.size)
            assertTrue(
                (0 until size).all { model.probabilityOfZero(it) == half },
                "every entry must start at $half",
            )
        }
    }

    @Test
    fun `an adaptation shift outside its bounds is rejected`() {
        for (shift in intArrayOf(Int.MIN_VALUE, -1, MIN_PROBABILITY_ADAPT_SHIFT - 1, MAX_PROBABILITY_ADAPT_SHIFT + 1)) {
            assertFailsWith<IllegalArgumentException>("shift=$shift") { BitModel(4, shift) }
        }
    }

    @Test
    fun `a smaller adaptation shift moves a probability faster`() {
        val fast = BitModel(1, MIN_PROBABILITY_ADAPT_SHIFT)
        val slow = BitModel(1, MAX_PROBABILITY_ADAPT_SHIFT)
        repeat(8) {
            fast.adapt(0, 0)
            slow.adapt(0, 0)
        }
        assertTrue(
            fast.probabilityOfZero(0) > slow.probabilityOfZero(0),
            "a smaller shift must move the probability further toward a zero bit",
        )
    }

    @Test
    fun `every adaptation shift round trips a skewed stream`() {
        val random = Random(2026)
        val bits = List(4000) { if (random.nextInt(8) == 0) 1 else 0 }
        for (shift in MIN_PROBABILITY_ADAPT_SHIFT..MAX_PROBABILITY_ADAPT_SHIFT) {
            val output = ByteArrayOutputStream()
            val encoder = RangeEncoder(output)
            val encodeModel = BitModel(1, shift)
            bits.forEach { encoder.encodeBit(encodeModel, 0, it) }
            encoder.finish()

            val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
            val decodeModel = BitModel(1, shift)
            assertEquals(bits, List(bits.size) { decoder.decodeBit(decodeModel, 0) }, "shift=$shift")
        }
    }

    // ---- Scenario 12: sizing bounds of the wide-value models -------------------------------------

    @Test
    fun `a matched bit tree width outside its bounds is rejected`() {
        for (bitCount in intArrayOf(Int.MIN_VALUE, -1, 0, MAX_MATCHED_BIT_COUNT + 1, Int.SIZE_BITS)) {
            assertFailsWith<IllegalArgumentException>("bitCount=$bitCount") {
                MatchedBitTreeModel(1, bitCount)
            }
        }
        // A width the bound admits sizes its trees without wrapping. The bound itself is the limit
        // of the Int addressing a slot, not of the heap, so instantiating it here would ask for
        // gigabytes.
        assertEquals(1 shl 16, MatchedBitTreeModel(1, 16).treeSize)
        // A context count that would overflow the bank is rejected rather than wrapping.
        assertFailsWith<IllegalArgumentException> { MatchedBitTreeModel(4, MAX_MATCHED_BIT_COUNT) }
        assertFailsWith<IllegalArgumentException> { MatchedBitTreeModel(0, 8) }
    }

    @Test
    fun `an excluded count outside its bounds is rejected`() {
        val model = excludedLiteralModel(1)
        val excluded = IntArray(1)
        for (count in intArrayOf(Int.MIN_VALUE, -1, MAX_EXCLUDED_COUNT + 1, Int.SIZE_BITS)) {
            assertFailsWith<IllegalArgumentException>("count=$count") {
                RangeEncoder(ByteArrayOutputStream()).encodeExcludedLiteral(model, 0, 7, excluded, count)
            }
        }
    }

    @Test
    fun `a byte round trips against every excluded count the coders accept`() {
        // The live set is a bit mask over the excluded values, so the widest accepted count is the
        // one that must still address it without overflowing.
        for (count in intArrayOf(0, 1, 8, MAX_EXCLUDED_COUNT)) {
            // Values the byte differs from, all distinct and none equal to it.
            val excluded = IntArray(count) { it + 1 }
            val value = 0

            val output = ByteArrayOutputStream()
            val encoder = RangeEncoder(output)
            encoder.encodeExcludedLiteral(excludedLiteralModel(1), 0, value, excluded, count)
            encoder.finish()

            val decoder = RangeDecoder(ByteArrayInputStream(output.toByteArray()))
            assertEquals(
                value,
                decoder.decodeExcludedLiteral(excludedLiteralModel(1), 0, excluded, count),
                "count=$count",
            )
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun encodeBits(bits: List<Int>): ByteArray {
        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        val model = BitModel(1)
        bits.forEach { encoder.encodeBit(model, 0, it) }
        encoder.finish()
        return output.toByteArray()
    }

    private fun decodeBits(bytes: ByteArray, count: Int): List<Int> {
        val decoder = RangeDecoder(ByteArrayInputStream(bytes))
        val model = BitModel(1)
        return List(count) { decoder.decodeBit(model, 0) }
    }

    private fun encodeOperations(operations: List<Op>, modelSize: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val encoder = RangeEncoder(output)
        val model = BitModel(modelSize)
        for (operation in operations) {
            when (operation) {
                is BitOp -> encoder.encodeBit(model, operation.index, operation.bit)
                is RawOp -> encoder.encodeRawBits(operation.value, operation.count)
            }
        }
        encoder.finish()
        return output.toByteArray()
    }

    private fun replayAndAssert(operations: List<Op>, encoded: ByteArray, modelSize: Int) {
        val decoder = RangeDecoder(ByteArrayInputStream(encoded))
        val model = BitModel(modelSize)
        for ((position, operation) in operations.withIndex()) {
            when (operation) {
                is BitOp -> assertEquals(operation.bit, decoder.decodeBit(model, operation.index), "op #$position")
                is RawOp ->
                    assertEquals(
                        mask(operation.value, operation.count),
                        decoder.decodeRawBits(operation.count),
                        "op #$position",
                    )
            }
        }
    }

    /**
     * The value a `decodeRawBits(width)` call must reproduce from an `encodeRawBits(value, width)`
     * call.
     */
    private fun mask(value: Int, width: Int): Int = when (width) {
        0 -> 0
        32 -> value
        else -> value and ((1 shl width) - 1)
    }

    private fun allOnes(width: Int): Int = when (width) {
        0 -> 0
        32 -> -1
        else -> (1 shl width) - 1
    }

    private sealed class Op

    private class BitOp(val index: Int, val bit: Int) : Op()

    private class RawOp(val value: Int, val count: Int) : Op()

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TrackingInputStream(data: ByteArray) : ByteArrayInputStream(data) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
