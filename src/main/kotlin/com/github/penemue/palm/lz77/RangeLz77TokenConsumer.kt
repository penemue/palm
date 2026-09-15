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

import com.github.penemue.palm.range.BitModel
import com.github.penemue.palm.range.MatchedBitTreeModel
import com.github.penemue.palm.range.RangeEncoder
import com.github.penemue.palm.range.encodeMatchedBitTree
import com.github.penemue.palm.util.bitLength
import com.github.penemue.palm.util.unsignedInt
import java.io.OutputStream

/**
 * Writes LZ77 tokens through an adaptive binary [RangeEncoder], so every field costs its modeled
 * information content rather than a whole number of bits.
 *
 * The models are never transmitted; the paired [RangeLz77TokenReader] rebuilds them in lock-step:
 * - **selector**: one bit per token, `0` for a literal and `1` for a match, coded in one of
 *   twice [SELECTOR_RUN_BUCKETS] contexts chosen by the run so far — the current run length bucketed
 *   as `1, 2, ..., >= SELECTOR_RUN_BUCKETS`, the literal buckets first and the match buckets after
 *   them. The stream opens in the first literal bucket, as if a literal preceded it.
 * - **literal**: each byte is coded MSB-first through a bit tree chosen by an order-1 context, the
 *   byte immediately preceding it in the reconstructed output. A match advances that context to the
 *   last byte it reproduces, so a literal following a match is coded against the byte that match
 *   ended on. A table holding, per context byte, the literal that last followed it supplies a
 *   *predicted* byte, and the coding follows
 *   [LZMA](https://en.wikipedia.org/wiki/Lempel%E2%80%93Ziv%E2%80%93Markov_chain_algorithm)'s
 *   matched-literal scheme: while the coded prefix still equals the predicted byte's prefix, bits are
 *   coded in a *matched* sub-tree keyed by the next predicted bit, and the remaining bits use the
 *   plain sub-tree once a coded bit diverges. Only literals enter that table.
 * - **length**: `length - minMatch` is split into a *short* range, the first
 *   `2^min(SHORT_LENGTH_BITS, lengthBits)` values, and a *long* range holding the rest, selected by
 *   a range flag. A short value is coded MSB-first through a bit tree that deep; the flag and that
 *   tree share `2 * LENGTH_PREV_GROUPS` contexts, chosen by whether this match follows a literal or
 *   a match and by the [lengthPrevGroup] of the previous match's length. A long value is coded
 *   through a `lengthBits`-deep bit tree, so the tree spans the whole long range, in one of two
 *   contexts: a match after a literal vs. a match after a match.
 * - **distance**: the field opens with a *rep* bit saying whether this match reuses one of the
 *   [Lz77Config.repDistanceCount] most recently used distances, coded in one of [REP_RUN_BUCKETS]
 *   contexts chosen by the match run so far. A hit is followed by the position in that list, coded
 *   as a unary chain of adaptive bits with one probability per position, so the most recent distance
 *   costs a single bit and the last position needs no bit at all. A miss is followed by the distance
 *   spelled out in full.
 * - **fresh distance**: `distance - 1` is split, as in
 *   [LZMA](https://en.wikipedia.org/wiki/Lempel%E2%80%93Ziv%E2%80%93Markov_chain_algorithm), into a
 *   *slot* — the number of significant bits of the value, coded through an adaptive bit tree —
 *   followed by the mantissa: the bits below the leading one. The high mantissa bits are emitted as
 *   equiprobable *raw* bits, while the low [DISTANCE_ALIGN_BITS] *align* bits are coded adaptively,
 *   one probability per absolute bit position. Only a miss reaches these models, so they adapt to
 *   the distances that are actually spelled out.
 *
 * A configuration that remembers no distances codes no rep bit at all, leaving the fresh distance
 * as the whole field. The list of remembered distances belongs to the compression operation and is
 * advanced by the driving loop once per emitted match; this consumer only queries it.
 *
 * Calling [finish] flushes the range coder without closing the destination; no explicit end marker is
 * written.
 *
 * @param config LZ77 geometry that bounds the length and distance fields.
 * @param output destination for the coded stream; flushed but not closed by [finish].
 * @param repDistances the operation's list of recent match distances a repeat is named against.
 */
class RangeLz77TokenConsumer(
    config: Lz77Config,
    output: OutputStream,
    repDistances: RepDistances,
) : Lz77TokenConsumer(config, repDistances) {

    private val encoder = RangeEncoder(output)
    private val lengthBits = config.lengthBits
    private val slotBits = config.windowBits.bitLength()
    private val selectorModel = BitModel(2 * SELECTOR_RUN_BUCKETS, SELECTOR_MODEL_ADAPT_SHIFT)
    private val literalModel = MatchedBitTreeModel(LITERAL_CONTEXTS, Byte.SIZE_BITS, LITERAL_MODEL_ADAPT_SHIFT)
    private val predictions = ByteArray(LITERAL_CONTEXTS)
    private val shortLengthBits = minOf(SHORT_LENGTH_BITS, lengthBits)
    private val shortLengthCount = 1 shl shortLengthBits
    private val lengthRangeModel = BitModel(2 * LENGTH_PREV_GROUPS, LENGTH_MODEL_ADAPT_SHIFT)
    private val shortLengthModel = BitModel(2 * LENGTH_PREV_GROUPS * shortLengthCount, LENGTH_MODEL_ADAPT_SHIFT)
    private val longLengthModel = BitModel(2 * (1 shl lengthBits), LENGTH_MODEL_ADAPT_SHIFT)

    // One bit tree over the distance slot (the value's significant-bit count).
    private val distanceSlotModel = BitModel(1 shl slotBits, DISTANCE_SLOT_MODEL_ADAPT_SHIFT)
    // One adaptive probability per absolute low mantissa bit position (the align bits).
    private val distanceAlignModel = BitModel(DISTANCE_ALIGN_BITS, DISTANCE_ALIGN_MODEL_ADAPT_SHIFT)
    // Rep bit contexts, bucketed by the match run preceding this match (see repContext).
    private val repModel = BitModel(REP_RUN_BUCKETS, REP_MODEL_ADAPT_SHIFT)
    // Positions the unary rep-index chain can code a bit at: the last index is implied by the ones before it, so it
    // needs no probability of its own.
    private val repIndexPositions = (config.repDistanceCount - 1).coerceAtLeast(0)
    // One adaptive probability per rep index position, in each match-run bucket (see repIndexContext).
    private val repIndexModel = BitModel(REP_INDEX_RUN_BUCKETS * repIndexPositions, REP_MODEL_ADAPT_SHIFT)
    // Selector context of the next token: the run so far, bucketed as described on the class. The literal buckets
    // occupy 0 until SELECTOR_RUN_BUCKETS and the match buckets the rest, so the previous token was a match iff it
    // reaches SELECTOR_RUN_BUCKETS, and every other run-keyed context is derived from it.
    private var runContext = 0
    private var previousByte = 0
    private var previousLengthGroup = LENGTH_PREV_GROUPS - 1

    override fun literal(value: Int) {
        encoder.encodeBit(selectorModel, runContext, 0)
        encodeLiteral(value, predictions[previousByte].unsignedInt)
        predictions[previousByte] = value.toByte()
        previousByte = value
        runContext = if (runContext < SELECTOR_RUN_BUCKETS) {
            minOf(runContext + 1, SELECTOR_RUN_BUCKETS - 1)
        } else {
            0
        }
    }

    override fun match(length: Int, distance: Int, lastByte: Int) {
        encoder.encodeBit(selectorModel, runContext, 1)
        encodeLength(length - config.minMatch)
        encodeDistance(distance)
        previousByte = lastByte
        runContext = if (runContext < SELECTOR_RUN_BUCKETS) {
            SELECTOR_RUN_BUCKETS
        } else {
            minOf(runContext + 1, 2 * SELECTOR_RUN_BUCKETS - 1)
        }
    }

    override fun finish() {
        encoder.finish()
    }

    /**
     * Capped length of the match run preceding this match, or a non-positive value after a literal.
     * [repContext] and [repIndexContext] clamp both ends, which is what folds every literal case
     * into their first bucket.
     */
    private fun matchRun(): Int = runContext - SELECTOR_RUN_BUCKETS + 1

    private fun encodeLiteral(value: Int, predicted: Int) =
        encoder.encodeMatchedBitTree(literalModel, previousByte, value, predicted)

    /**
     * Codes a zero-based [value] `length - minMatch` as a range flag plus the value within that
     * range, and remembers it for the next match's context.
     *
     * @param value the length field, in `0 until shortLengthCount + 2^lengthBits`.
     */
    private fun encodeLength(value: Int) {
        val context = lengthContext()
        if (value < shortLengthCount) {
            encoder.encodeBit(lengthRangeModel, context, 0)
            encoder.encodeBitTree(shortLengthModel, context shl shortLengthBits, value, shortLengthBits)
        } else {
            encoder.encodeBit(lengthRangeModel, context, 1)
            encoder.encodeBitTree(
                longLengthModel, longLengthContext() shl lengthBits, value - shortLengthCount, lengthBits,
            )
        }
        previousLengthGroup = lengthPrevGroup(value)
    }

    /**
     * Context of the range flag and of a short length: whether this match follows a match rather
     * than a literal, refined by the previous match's length group.
     */
    private fun lengthContext(): Int = longLengthContext() * LENGTH_PREV_GROUPS + previousLengthGroup

    /**
     * Context of a long length: `1` when this match follows another match, `0` when it follows a
     * literal. The run counter still holds the pre-token state here.
     */
    private fun longLengthContext(): Int = if (runContext >= SELECTOR_RUN_BUCKETS) 1 else 0

    /**
     * Context of the rep bit: the match run preceding this match, capped at the last bucket, so a
     * match opening a run after literals is told apart from one continuing a run of matches.
     */
    private fun repContext(): Int = matchRun().coerceIn(0, REP_RUN_BUCKETS - 1)

    /**
     * Bucket of the rep index chain, the same preceding match run as [repContext] but capped at
     * [REP_INDEX_RUN_BUCKETS].
     */
    private fun repIndexContext(): Int = matchRun().coerceIn(0, REP_INDEX_RUN_BUCKETS - 1)

    /**
     * Codes the distance field: the rep bit, then either the position of [distance] among the
     * remembered distances or the full fresh-distance encoding. A stream that remembers no distances
     * codes the fresh encoding alone.
     *
     * @param distance the match distance, in `1..config.maxDistance`.
     */
    private fun encodeDistance(distance: Int) {
        if (repDistances.size == 0) {
            encodeFreshDistance(distance - 1)
            return
        }
        val repIndex = repDistances.indexOf(distance)
        if (repIndex < 0) {
            encoder.encodeBit(repModel, repContext(), 0)
            encodeFreshDistance(distance - 1)
        } else {
            encoder.encodeBit(repModel, repContext(), 1)
            encodeRepIndex(repIndex)
        }
    }

    /**
     * Codes a rep index as a unary chain: a `0` stops at the current position, a `1` moves on to the
     * next one. The last position is reached only after every earlier one was rejected, so it needs
     * no bit of its own.
     *
     * @param repIndex position among the remembered distances, in `0 until repDistances.size`.
     */
    private fun encodeRepIndex(repIndex: Int) {
        val base = repIndexContext() * repIndexPositions
        repeat(repIndex) { i ->
            encoder.encodeBit(repIndexModel, base + i, 1)
        }
        if (repIndex < repDistances.size - 1) {
            encoder.encodeBit(repIndexModel, base + repIndex, 0)
        }
    }

    /**
     * Codes a zero-based [value] `distance - 1` as an adaptive *slot* (its significant-bit count)
     * plus the mantissa bits below the leading one. The leading one is implicit in the slot, so a
     * slot of `n > 1` carries `n - 1` mantissa bits and slots `0`/`1` carry none. Of those mantissa
     * bits the high ones are equiprobable *raw* bits, while the low [DISTANCE_ALIGN_BITS] align bits
     * are coded adaptively with a per-position context, most significant first in each group.
     */
    private fun encodeFreshDistance(value: Int) {
        val slot = value.bitLength()
        encoder.encodeBitTree(distanceSlotModel, 0, slot, slotBits)
        // Slots 0 and 1 carry no mantissa at all, so nothing follows the slot for them.
        val mantissaBits = slot - 1
        val rawBits = mantissaBits - DISTANCE_ALIGN_BITS
        val alignBits = if (rawBits > 0) {
            encoder.encodeRawBits(value ushr DISTANCE_ALIGN_BITS, rawBits)
            DISTANCE_ALIGN_BITS
        } else {
            mantissaBits
        }
        for (bit in alignBits - 1 downTo 0) {
            encoder.encodeBit(distanceAlignModel, bit, (value ushr bit) and 1)
        }
    }
}

/**
 * How many order-1 literal contexts the range format keeps: one per value of the previous output
 * byte.
 */
internal const val LITERAL_CONTEXTS = 256

/**
 * How many of a distance mantissa's lowest bits the range format models adaptively (the *align*
 * bits) instead of writing them as equiprobable raw bits.
 */
internal const val DISTANCE_ALIGN_BITS = 4

/**
 * How many of a length field's lowest values form the *short* range the range format codes through
 * its richly contextualized bit tree: values `0 until 2^SHORT_LENGTH_BITS`.
 */
internal const val SHORT_LENGTH_BITS = 4

/**
 * How many length values the short range holds for a geometry of [lengthBits], which narrows the
 * range when the geometry is too small to fill it.
 *
 * @param lengthBits the geometry's [Lz77Config.lengthBits].
 */
internal fun shortLengthCount(lengthBits: Int): Int = 1 shl minOf(SHORT_LENGTH_BITS, lengthBits)

/**
 * How many groups [lengthPrevGroup] folds a length field into, so that a length is coded in the
 * context of the previous match's length.
 */
internal const val LENGTH_PREV_GROUPS = 8

/**
 * Group a length field belongs to as the context of the *next* match's length: a logarithmic scale
 * widening as lengths grow, so that a rarely seen long length still shares statistics with its
 * neighbors.
 *
 * @param value a length field, `length - minMatch`.
 */
internal fun lengthPrevGroup(value: Int): Int = when {
    value < 8 -> value shr 1
    value < 12 -> 4
    value < 16 -> 5
    value < 24 -> 6
    else -> LENGTH_PREV_GROUPS - 1
}

/**
 * How many run-length buckets the selector keeps per token type, `2 * SELECTOR_RUN_BUCKETS` contexts
 * in all, with the literal buckets in the low half and the match buckets in the high half. A run of
 * `n` tokens of the same type maps to bucket `min(n - 1, SELECTOR_RUN_BUCKETS - 1)`, i.e. runs
 * `1, 2, ..., >= SELECTOR_RUN_BUCKETS`.
 */
internal const val SELECTOR_RUN_BUCKETS = 16

/**
 * How many buckets the rep bit keeps over the match run *preceding* this match, where bucket `0` is
 * a match opening a run after literals.
 */
internal const val REP_RUN_BUCKETS = 3

/**
 * How many buckets the rep *index* chain keeps over the same preceding match run the rep bit uses,
 * on top of its per-position probabilities.
 */
internal const val REP_INDEX_RUN_BUCKETS = 4

/**
 * Rate the selector contexts settle at, tracking how likely a match becomes once a run of one token
 * type is under way.
 */
internal const val SELECTOR_MODEL_ADAPT_SHIFT = 5

/**
 * Rate the literal tree settles at, tracking which byte values follow each order-1 context.
 */
internal const val LITERAL_MODEL_ADAPT_SHIFT = 5

/**
 * Rate the length models settle at, tracking how long matches run. Shared by the range flag and
 * both length trees.
 */
internal const val LENGTH_MODEL_ADAPT_SHIFT = 5

/**
 * Rate the rep models settle at, tracking how often a match reuses a remembered distance and which
 * one it names. Shared by the rep-presence bit and the rep-index chain.
 */
internal const val REP_MODEL_ADAPT_SHIFT = 4

/**
 * Rate the distance slot tree settles at, tracking the magnitude classes spelled-out distances fall
 * into.
 */
internal const val DISTANCE_SLOT_MODEL_ADAPT_SHIFT = 6

/**
 * Rate the distance align models settle at, tracking the low bits of a spelled-out distance, where a
 * regular record size in the input shows up as a recurring alignment.
 */
internal const val DISTANCE_ALIGN_MODEL_ADAPT_SHIFT = 7
