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

import java.io.IOException
import java.io.InputStream

/**
 * The exact inverse of [RangeEncoder]: a binary range decoder with adaptive bit probabilities.
 *
 * Each binary decision is reconstructed against a probability held in a caller-owned [BitModel] and
 * adapted after the decoded bit, so an encoder and a decoder that visit the same indices in the same
 * order stay in lock-step with no table on the wire.
 *
 * The constructor primes the coder from the first five bytes of the stream, of which the leading one
 * is always zero and is discarded. Reaching end of stream mid-decode throws [IOException].
 *
 * This class is **not** thread-safe and does not close [input].
 *
 * @param input source of the coded bytes produced by a [RangeEncoder].
 */
class RangeDecoder(private val input: InputStream) {

    private var range = 0xFFFFFFFFL
    private var code = 0L

    init {
        readByte()
        repeat(4) { code = (code shl 8) or readByte().toLong() }
    }

    /**
     * Decodes one bit against the adaptive probability at [model]`[index]`, adapting that
     * probability exactly as [RangeEncoder.encodeBit] did.
     *
     * @param model the bit model this decision draws its probability from.
     * @param index which probability in [model] applies; must match the encoder's context.
     * @return the decoded bit, 0 or 1.
     */
    fun decodeBit(model: BitModel, index: Int): Int {
        val bound = (range ushr PROBABILITY_BITS) * model.probabilityOfZero(index)
        val bit: Int
        // The coder invariant keeps both code and bound in 0 until 2^32, so this compares them
        // as the unsigned 32-bit quantities they are.
        if (code < bound) {
            range = bound
            bit = 0
        } else {
            code -= bound
            range -= bound
            bit = 1
        }
        model.adapt(index, bit)
        while (range < RANGE_TOP) {
            range = range shl 8
            code = ((code shl 8) or readByte().toLong()) and 0xFFFFFFFFL
        }
        return bit
    }

    /**
     * Decodes [count] **equiprobable** bits, most significant first, the inverse of
     * [RangeEncoder.encodeRawBits]. Uses no model.
     *
     * @param count how many bits to read, `0..32`.
     * @return the decoded bits as an `Int`, right-aligned.
     */
    fun decodeRawBits(count: Int): Int {
        var result = 0
        repeat(count) {
            range = range ushr 1
            // 32-bit modular (code - range): its bit 31 is the borrow. The coder invariant keeps
            // 0 <= code < range, so a non-negative difference is below 2^31 and reads as bit 0 = 1.
            val difference = (code - range) and 0xFFFFFFFFL
            val bit = 1 - (difference ushr 31).toInt()
            if (bit == 1) code = difference
            result = (result shl 1) or bit
            if (range < RANGE_TOP) {
                range = range shl 8
                code = ((code shl 8) or readByte().toLong()) and 0xFFFFFFFFL
            }
        }
        return result
    }

    /**
     * Decodes a [bitCount]-bit symbol most significant first from an adaptive **bit tree** held in
     * [model] and offset by [base], the exact inverse of [RangeEncoder.encodeBitTree]. Walks the
     * tree from node `1`, decoding one bit per level against the node reached so far.
     *
     * @param model the bit model holding this tree's `2^bitCount` nodes (index `base + node`).
     * @param base offset of this tree's node `1` within [model]; must match the encoder's context.
     * @param bitCount the tree depth, i.e. how many bits to decode.
     * @return the decoded symbol, right-aligned.
     */
    fun decodeBitTree(model: BitModel, base: Int, bitCount: Int): Int {
        var node = 1
        repeat(bitCount) {
            node = (node shl 1) or decodeBit(model, base + node)
        }
        return node - (1 shl bitCount)
    }

    /**
     * @throws IOException on a premature end of stream, i.e. a truncated payload.
     */
    private fun readByte(): Int = input.read().also {
        if (it < 0) throw IOException("Truncated range-coded stream")
    }
}
