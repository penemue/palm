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

/**
 * Widest value [MatchedBitTreeModel] codes: beyond this the trees of a single context no longer fit
 * a positive [Int] of slots, whatever the context count.
 */
const val MAX_MATCHED_BIT_COUNT = Int.SIZE_BITS - 3

/**
 * Adaptive model of a value coded against a *predicted* value both sides already hold: the
 * matched-literal scheme of
 * [LZMA](https://en.wikipedia.org/wiki/Lempel%E2%80%93Ziv%E2%80%93Markov_chain_algorithm), over an
 * alphabet of any width.
 *
 * A value is coded most significant bit first. While the coded prefix still equals the predicted
 * value's prefix, the bits go through a *matched* bit tree selected by the next predicted bit; once a
 * coded bit diverges, the remaining bits go through the *plain* tree. Each of the [contexts] contexts
 * owns all three trees, and the caller picks the context per value.
 *
 * Instances are stateful and **not** thread-safe: one per coder, per stream.
 *
 * @param contexts number of independent contexts the caller indexes this model by.
 * @property bitCount width of a coded value, which is also the depth of each tree, in
 * `1..`[MAX_MATCHED_BIT_COUNT].
 * @param adaptShift adaptation rate, in `MIN_PROBABILITY_ADAPT_SHIFT..MAX_PROBABILITY_ADAPT_SHIFT`.
 */
class MatchedBitTreeModel(
    contexts: Int,
    internal val bitCount: Int,
    adaptShift: Int = DEFAULT_PROBABILITY_ADAPT_SHIFT,
) {
    init {
        require(bitCount in 1..MAX_MATCHED_BIT_COUNT) {
            "bitCount must be in 1..$MAX_MATCHED_BIT_COUNT but was $bitCount"
        }
        // Counted in Long, since the product is exactly what must be shown not to overflow an Int.
        val slots = contexts.toLong() * TREES_PER_CONTEXT * (1L shl bitCount)
        require(contexts > 0 && slots <= Int.MAX_VALUE) {
            "$contexts contexts of $bitCount bits need $slots slots, past the Int.MAX_VALUE limit"
        }
    }

    /**
     * Slots one tree occupies. A tree over [bitCount] bits has `2^bitCount - 1` internal nodes,
     * addressed as `1 until 2^bitCount` by the bits coded so far; slot `0` stays unused, which keeps
     * the per-context stride a power of two.
     */
    internal val treeSize = 1 shl bitCount

    /**
     * The probabilities of every tree of every context, in one bank: a coder addresses a node by
     * the context, the tree within it and the path taken so far.
     */
    internal val model = BitModel(contexts * TREES_PER_CONTEXT * treeSize, adaptShift)
}

/**
 * Codes the low [MatchedBitTreeModel.bitCount] bits of [value] through [model]'s [context], using
 * [predicted] as a prior. The paired [decodeMatchedBitTree] inverts it exactly.
 *
 * @param context which of the model's contexts this value is coded in.
 * @param predicted the value expected here, read as the same width as [value].
 */
fun RangeEncoder.encodeMatchedBitTree(model: MatchedBitTreeModel, context: Int, value: Int, predicted: Int) {
    val treeSize = model.treeSize
    val base = context * TREES_PER_CONTEXT * treeSize
    var node = 1
    var matched = true
    var i = model.bitCount
    while (--i >= 0) {
        val predictedBit = (predicted ushr i) and 1
        val bit = (value ushr i) and 1
        val index = if (matched) {
            base + (1 + predictedBit) * treeSize + node
        } else {
            base + node
        }
        encodeBit(model.model, index, bit)
        node = (node shl 1) or bit
        matched = matched && bit == predictedBit
    }
}

/**
 * Decodes one value from [model]'s [context], using [predicted] as a prior, the exact inverse of
 * [encodeMatchedBitTree].
 *
 * @param context which of the model's contexts this value was coded in.
 * @param predicted the value expected here, read as [MatchedBitTreeModel.bitCount] bits.
 * @return the decoded value, right-aligned.
 */
fun RangeDecoder.decodeMatchedBitTree(model: MatchedBitTreeModel, context: Int, predicted: Int): Int {
    val treeSize = model.treeSize
    val base = context * TREES_PER_CONTEXT * treeSize
    var node = 1
    var matched = true
    var i = model.bitCount
    while (--i >= 0) {
        val predictedBit = (predicted ushr i) and 1
        val index = if (matched) {
            base + (1 + predictedBit) * treeSize + node
        } else {
            base + node
        }
        val bit = decodeBit(model.model, index)
        node = (node shl 1) or bit
        matched = matched && bit == predictedBit
    }
    return node - treeSize
}

/**
 * How many trees a context keeps: the plain one plus a matched one per value of the bit predicted
 * next.
 */
private const val TREES_PER_CONTEXT = 3
