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

import java.io.OutputStream

/**
 * A binary range (arithmetic) encoder with adaptive bit probabilities, in the style of the LZMA
 * range coder.
 *
 * It codes one binary decision at a time. Each decision carries its own adaptive probability, held
 * in a caller-owned [BitModel] and selected by an index the caller computes from its context. The
 * probability is read and then adapted toward the bit just coded, so an encoder and a [RangeDecoder]
 * that visit the same indices in the same order reproduce the same probabilities with no table on the
 * wire.
 *
 * The coder keeps a 32-bit range and a low bound with one carry byte of headroom. Whenever the range
 * would fall below [RANGE_TOP] it renormalizes, shipping the settled high byte through a carry-aware
 * cache so a late `+1` can still propagate. [finish] flushes the five pending bytes.
 *
 * This class is **not** thread-safe, writes to [out] incrementally, and does not close it.
 *
 * @param out destination for the coded bytes.
 */
class RangeEncoder(private val out: OutputStream) {

    private var low = 0L
    private var range = 0xFFFFFFFFL
    private var cache = 0
    private var cacheSize = 1L

    /**
     * Encodes one [bit] (0 or 1) against the adaptive probability at [model]`[index]`, then adapts
     * that probability toward the coded bit.
     *
     * @param model the bit model this decision draws its probability from.
     * @param index which probability in [model] applies, i.e. the caller's context.
     * @param bit the value to code; any non-zero value is treated as one.
     */
    fun encodeBit(model: BitModel, index: Int, bit: Int) {
        // Split the current range in proportion to the zero-bit probability.
        val bound = (range ushr PROBABILITY_BITS) * model.probabilityOfZero(index)
        if (bit == 0) {
            range = bound
        } else {
            low += bound
            range -= bound
        }
        model.adapt(index, bit)
        while (range < RANGE_TOP) {
            range = range shl 8
            shiftLow()
        }
    }

    /**
     * Encodes the low [count] bits of [value], most significant first, as **equiprobable** bits with
     * no adaptive model, the range-coder equivalent of writing raw bits. The paired
     * [RangeDecoder.decodeRawBits] inverts it exactly.
     *
     * @param value the bits to emit; only its low [count] bits are read.
     * @param count how many bits to emit, `0..32`.
     */
    fun encodeRawBits(value: Int, count: Int) {
        var i = count
        while (--i >= 0) {
            range = range ushr 1
            if (((value ushr i) and 1) != 0) low += range
            if (range < RANGE_TOP) {
                range = range shl 8
                shiftLow()
            }
        }
    }

    /**
     * Codes the low [bitCount] bits of [value] most significant first through an adaptive **bit
     * tree** held in [model] and offset by [base]: a complete binary tree whose node `1` is the
     * root and whose leaves are the `2^bitCount` possible values. Each bit is coded by [encodeBit]
     * against the node reached by the path taken so far (`node = (node shl 1) or bit`), so every
     * prefix owns its own adaptive probability. The paired [RangeDecoder.decodeBitTree] inverts it
     * exactly.
     *
     * @param model the bit model holding this tree's `2^bitCount` nodes (index `base + node`).
     * @param base offset of this tree's node `1` within [model], i.e. the caller's context.
     * @param value the symbol to code; only its low [bitCount] bits are read.
     * @param bitCount the tree depth, i.e. how many bits of [value] to code.
     */
    fun encodeBitTree(model: BitModel, base: Int, value: Int, bitCount: Int) {
        var node = 1
        var i = bitCount
        while (--i >= 0) {
            val bit = (value ushr i) and 1
            encodeBit(model, base + node, bit)
            node = (node shl 1) or bit
        }
    }

    /**
     * Flushes the five bytes still buffered in the coder, completing the stream. Call exactly once,
     * after the last coded bit.
     */
    fun finish() {
        repeat(5) { shiftLow() }
    }

    /**
     * Ships the top byte of [low] once it can no longer be changed by a carry, resolving any pending
     * carry into the cached byte and the run of `0xFF` bytes it was holding back.
     */
    private fun shiftLow() {
        val low32 = low and 0xFFFFFFFFL
        val carry = (low ushr 32).toInt()
        if (low32 < 0xFF000000L || carry != 0) {
            var temp = cache
            do {
                out.write((temp + carry) and 0xFF)
                temp = 0xFF
            } while (--cacheSize != 0L)
            cache = (low32 ushr 24).toInt() and 0xFF
        }
        cacheSize++
        low = (low32 and 0x00FFFFFFL) shl 8
    }
}
