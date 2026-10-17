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
 * Most values [encodeExcludedLiteral] and [decodeExcludedLiteral] code a byte against: each coder
 * tracks which of them are still live in the bits of one [Int].
 */
const val MAX_EXCLUDED_COUNT = Int.SIZE_BITS - 1

/**
 * Builds the model [encodeExcludedLiteral] and [decodeExcludedLiteral] code through, with the default
 * live-count buckets on the default [RangeFormat].
 *
 * @param contexts how many independent contexts the caller indexes the model by.
 * @param adaptShift adaptation rate, in `MIN_PROBABILITY_ADAPT_SHIFT..MAX_PROBABILITY_ADAPT_SHIFT`.
 */
fun excludedLiteralModel(contexts: Int, adaptShift: Int = DEFAULT_PROBABILITY_ADAPT_SHIFT): BitModel =
    excludedLiteralModel(contexts, adaptShift, LIVE_COUNT_BUCKETS, DEFAULT_RANGE_FORMAT)

/**
 * Builds the model [encodeExcludedLiteral] and [decodeExcludedLiteral] code through.
 *
 * @param contexts how many independent contexts the caller indexes the model by.
 * @param adaptShift adaptation rate, from the warm-up rate of [format] up to one shift below its
 * probability bits.
 * @param liveCountBuckets how many buckets the live count folds into, at least
 * [MIN_LIVE_COUNT_BUCKETS]; this builder, the encoder and the decoder must all be given the same
 * value, since it sizes the model and addresses it alike.
 * @param format probability precision used by the model.
 */
fun excludedLiteralModel(
    contexts: Int,
    adaptShift: Int,
    liveCountBuckets: Int,
    format: RangeFormat,
): BitModel {
    requireLiveCountBuckets(liveCountBuckets)
    return BitModel(contexts * excludedTreesPerContext(liveCountBuckets) * BYTE_TREE_SIZE, adaptShift, format)
}

/**
 * Codes a byte known to differ from every one of the [excluded] values, through [model]'s [context].
 *
 * The walk is the matched-literal one of
 * [LZMA](https://en.wikipedia.org/wiki/Lempel%E2%80%93Ziv%E2%80%93Markov_chain_algorithm), most
 * significant bit first, but run against values the byte cannot be rather than one it might equal. An
 * excluded value stays *live* while its prefix equals the prefix coded so far; a bit goes through a
 * *matched* tree keyed by the next bit of the first live value and by a bucket of how many are still
 * live, and through the *plain* tree once none is. The paired [decodeExcludedLiteral] inverts it
 * exactly.
 *
 * @param model a model built by [excludedLiteralModel].
 * @param context which of the model's contexts this byte is coded in.
 * @param value the byte to code, in `0..255`.
 * @param excluded the values the byte is known not to equal. Their order is significant: the earlier
 * a value sits, the more of the byte it biases, so the caller leads with the one it trusts most.
 * @param excludedCount how many leading entries of [excluded] apply here, in
 * `0..`[MAX_EXCLUDED_COUNT]; the rest of the array is ignored.
 * @param liveCountBuckets how many buckets the live count folds into, at least
 * [MIN_LIVE_COUNT_BUCKETS]; must be the value [model] was built with, and the value the decoder is
 * given.
 */
@JvmOverloads
fun RangeEncoder.encodeExcludedLiteral(
    model: BitModel,
    context: Int,
    value: Int,
    excluded: IntArray,
    excludedCount: Int,
    liveCountBuckets: Int = LIVE_COUNT_BUCKETS,
) {
    val base = contextBase(context, excludedCount, liveCountBuckets)
    var live = (1 shl excludedCount) - 1
    var node = 1
    var i = Byte.SIZE_BITS
    while (--i >= 0) {
        val bit = (value ushr i) and 1
        encodeBit(model, base + subTreeOf(excluded, live, i, liveCountBuckets) * BYTE_TREE_SIZE + node, bit)
        node = (node shl 1) or bit
        live = narrow(excluded, live, i, bit)
    }
}

/**
 * Decodes one byte from [model]'s [context], the exact inverse of [encodeExcludedLiteral].
 *
 * @param model a model built by [excludedLiteralModel].
 * @param context which of the model's contexts this byte was coded in.
 * @param excluded the values the byte is known not to equal. Their order is significant: the earlier
 * a value sits, the more of the byte it biases, so the caller leads with the one it trusts most.
 * @param excludedCount how many leading entries of [excluded] apply here, in
 * `0..`[MAX_EXCLUDED_COUNT]; the rest of the array is ignored.
 * @param liveCountBuckets how many buckets the live count folds into, at least
 * [MIN_LIVE_COUNT_BUCKETS]; must be the value [model] was built with, and the value the encoder was
 * given.
 * @return the decoded byte, in `0..255`.
 */
@JvmOverloads
fun RangeDecoder.decodeExcludedLiteral(
    model: BitModel,
    context: Int,
    excluded: IntArray,
    excludedCount: Int,
    liveCountBuckets: Int = LIVE_COUNT_BUCKETS,
): Int {
    val base = contextBase(context, excludedCount, liveCountBuckets)
    var live = (1 shl excludedCount) - 1
    var node = 1
    var i = Byte.SIZE_BITS
    while (--i >= 0) {
        val bit = decodeBit(model, base + subTreeOf(excluded, live, i, liveCountBuckets) * BYTE_TREE_SIZE + node)
        node = (node shl 1) or bit
        live = narrow(excluded, live, i, bit)
    }
    return node - BYTE_TREE_SIZE
}

/**
 * Index of the first probability of [context] in a model folding the live count into
 * [liveCountBuckets] buckets.
 *
 * @throws IllegalArgumentException when [excludedCount] is outside `0..`[MAX_EXCLUDED_COUNT] or
 * [liveCountBuckets] is below [MIN_LIVE_COUNT_BUCKETS].
 */
private fun contextBase(context: Int, excludedCount: Int, liveCountBuckets: Int): Int {
    require(excludedCount in 0..MAX_EXCLUDED_COUNT) {
        "excludedCount must be in 0..$MAX_EXCLUDED_COUNT but was $excludedCount"
    }
    requireLiveCountBuckets(liveCountBuckets)
    return context * excludedTreesPerContext(liveCountBuckets) * BYTE_TREE_SIZE
}

/**
 * Tree a bit is coded through: the plain one when nothing is live, otherwise the matched tree of the
 * bucket the live count falls in, picked by the next bit of the leading live value.
 */
private fun subTreeOf(excluded: IntArray, live: Int, bitIndex: Int, liveCountBuckets: Int): Int {
    if (live == 0) return 0
    val bucket = minOf(live.countOneBits() - 1, liveCountBuckets - 1)
    val leadingBit = (excluded[live.countTrailingZeroBits()] ushr bitIndex) and 1
    return 1 + bucket * MATCHED_TREES_PER_BUCKET + leadingBit
}

/**
 * Drops from [live] every value whose bit at [bitIndex] differs from the [bit] just coded, leaving
 * those still agreeing with the coded prefix.
 */
private fun narrow(excluded: IntArray, live: Int, bitIndex: Int, bit: Int): Int {
    var remaining = live
    var result = 0
    while (remaining != 0) {
        val index = remaining.countTrailingZeroBits()
        val agrees = ((excluded[index] ushr bitIndex) xor bit xor 1) and 1
        result = result or (agrees shl index)
        remaining = remaining and (remaining - 1)
    }
    return result
}

/**
 * Default number of buckets the count of values still live at a coded bit is folded into, counting
 * from one upwards and saturating at the last bucket.
 */
private const val LIVE_COUNT_BUCKETS = 2

/**
 * Fewest live-count buckets a model may fold into: a bucket count below it leaves a live value no
 * matched tree to be coded through.
 */
const val MIN_LIVE_COUNT_BUCKETS = 1

/**
 * @throws IllegalArgumentException when [liveCountBuckets] is below [MIN_LIVE_COUNT_BUCKETS].
 */
private fun requireLiveCountBuckets(liveCountBuckets: Int) {
    require(liveCountBuckets >= MIN_LIVE_COUNT_BUCKETS) {
        "liveCountBuckets must be at least $MIN_LIVE_COUNT_BUCKETS but was $liveCountBuckets"
    }
}

/**
 * Matched trees a bucket owns: one per value of the next bit of the bucket's leading live value.
 */
private const val MATCHED_TREES_PER_BUCKET = 2

/**
 * Trees a context owns: the plain one, reached only once nothing is live, plus the matched trees of
 * every bucket.
 */
private fun excludedTreesPerContext(liveCountBuckets: Int) =
    1 + liveCountBuckets * MATCHED_TREES_PER_BUCKET

/**
 * Nodes a tree over a byte is addressed by: the bits coded so far, leaving slot 0 unused so that the
 * per-tree stride is a power of two.
 */
private const val BYTE_TREE_SIZE = 1 shl Byte.SIZE_BITS
