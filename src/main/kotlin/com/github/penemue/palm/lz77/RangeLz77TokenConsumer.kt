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
 * - **selector**: one bit per token, `0` for a literal and `1` for a match, coded in one of twice
 *   the format's selector run buckets chosen by the run so far — the current run length bucketed as
 *   `1, 2, ...` up to the last bucket, the literal buckets first and the match buckets after them.
 *   The stream opens in the first literal bucket, as if a literal preceded it.
 * - **literal**: each byte is coded MSB-first through a bit tree chosen by an order-1 context, the
 *   byte immediately preceding it in the reconstructed output. A match advances that context to the
 *   last byte it reproduces, so a literal following a match is coded against the byte that match
 *   ended on. A table holding, per context byte, the literal that last followed it supplies a
 *   *predicted* byte, and the coding follows
 *   [LZMA](https://en.wikipedia.org/wiki/Lempel%E2%80%93Ziv%E2%80%93Markov_chain_algorithm)'s
 *   matched-literal scheme: while the coded prefix still equals the predicted byte's prefix, bits are
 *   coded in a *matched* sub-tree keyed by the next predicted bit, and the remaining bits use the
 *   plain sub-tree once a coded bit diverges. Only literals enter that table.
 * - **length**: `length - minMatch` is split into a *short* range, the lowest values the format's
 *   short-length width spans, narrowed to `lengthBits` when the geometry offers fewer, and a *long*
 *   range holding the rest, selected by a range flag. A short value is coded MSB-first through a bit
 *   tree that deep; the flag and that tree share twice the format's previous-length groups as
 *   contexts, chosen by whether this match follows a literal or a match and by the
 *   [lengthPrevGroup] of the previous match's length. A long value is coded
 *   through a `lengthBits`-deep bit tree, so the tree spans the whole long range, in one of two
 *   contexts: a match after a literal vs. a match after a match.
 * - **distance**: the field opens with a *rep* bit saying whether this match reuses one of the
 *   [Lz77Config.repDistanceCount] most recently used distances, coded in one of the format's rep
 *   run buckets chosen by the match run so far. A hit is followed by the position in that list, coded
 *   as a unary chain of adaptive bits with one probability per position, so the most recent distance
 *   costs a single bit and the last position needs no bit at all. A miss is followed by the distance
 *   spelled out in full.
 * - **fresh distance**: `distance - 1` is split, as in
 *   [LZMA](https://en.wikipedia.org/wiki/Lempel%E2%80%93Ziv%E2%80%93Markov_chain_algorithm), into a
 *   *slot* — the number of significant bits of the value, coded through an adaptive bit tree —
 *   followed by the mantissa: the bits below the leading one. The high mantissa bits are emitted as
 *   equiprobable *raw* bits, while the format's low *align* bits are coded adaptively, one
 *   probability per absolute bit position. Only a miss reaches these models, so they adapt to
 *   the distances that are actually spelled out.
 *
 * A configuration that remembers no distances codes no rep bit at all, leaving the fresh distance
 * as the whole field. The list of remembered distances belongs to the compression operation and is
 * advanced by the driving loop once per emitted match; this consumer only queries it.
 *
 * Calling [finish] flushes the range coder without closing the destination; no explicit end marker is
 * written.
 *
 * @param config LZ77 geometry that bounds the length and distance fields; its longest match must be
 * the longest one [format] can code.
 * @param output destination for the coded stream.
 * @param repDistances the operation's list of recent match distances a repeat is named against.
 * @param format parameter set that defines the token models and range coder for the stream.
 * @throws IllegalArgumentException when the geometry reaches past the length alphabet [format]
 * codes.
 */
class RangeLz77TokenConsumer internal constructor(
    config: Lz77Config,
    output: OutputStream,
    repDistances: RepDistances,
    private val format: Lz77Format,
) : Lz77TokenConsumer(config, repDistances) {

    /**
     * A consumer coding with the parameter set every stream is written with now.
     */
    constructor(config: Lz77Config, output: OutputStream, repDistances: RepDistances) :
        this(config, output, repDistances, LZ77_CURRENT_FORMAT)

    init {
        require(config.maxMatch == format.maxMatch(config)) {
            "maxMatch ${config.maxMatch} differs from the ${format.maxMatch(config)} bytes" +
                " format version ${format.version} can code"
        }
    }

    private val encoder = RangeEncoder(output, format.range)
    private val lengthBits = config.lengthBits
    private val slotBits = config.windowBits.bitLength()
    private val selectorModel = BitModel(
        2 * format.selectorRunBuckets,
        format.selectorModelAdaptShift,
        format.range,
    )
    private val literalModel = MatchedBitTreeModel(
        LITERAL_CONTEXTS,
        Byte.SIZE_BITS,
        format.literalModelAdaptShift,
        format.range,
    )
    private val predictions = ByteArray(LITERAL_CONTEXTS)
    private val shortLengthBits = minOf(format.shortLengthBits, lengthBits)
    private val shortLengthCount = 1 shl shortLengthBits
    private val lengthRangeModel = BitModel(
        2 * format.lengthPreviousGroups,
        format.lengthModelAdaptShift,
        format.range,
    )
    private val shortLengthModel = BitModel(
        2 * format.lengthPreviousGroups * shortLengthCount,
        format.lengthModelAdaptShift,
        format.range,
    )
    private val longLengthModel = BitModel(
        2 * (1 shl lengthBits),
        format.lengthModelAdaptShift,
        format.range,
    )

    // One bit tree over the distance slot (the value's significant-bit count).
    private val distanceSlotModel = BitModel(
        1 shl slotBits,
        format.distanceSlotModelAdaptShift,
        format.range,
    )
    // One adaptive probability per absolute low mantissa bit position (the align bits).
    private val distanceAlignModel = BitModel(
        format.distanceAlignBits,
        format.distanceAlignModelAdaptShift,
        format.range,
    )
    // Rep bit contexts, bucketed by the match run preceding this match (see repContext).
    private val repModel = BitModel(format.repRunBuckets, format.repModelAdaptShift, format.range)
    // Positions the unary rep-index chain can code a bit at: the last index is implied by the ones before it, so it
    // needs no probability of its own.
    private val repIndexPositions = (config.repDistanceCount - 1).coerceAtLeast(0)
    // One adaptive probability per rep index position, in each match-run bucket (see repIndexContext).
    private val repIndexModel = BitModel(
        format.repIndexRunBuckets * repIndexPositions,
        format.repModelAdaptShift,
        format.range,
    )
    // Selector context of the next token: the run so far, bucketed as described on the class. The literal buckets
    // occupy 0 until format.selectorRunBuckets and the match buckets the rest, so the previous token was a match
    // iff it reaches format.selectorRunBuckets, and every other run-keyed context is derived from it.
    private var runContext = 0
    private var previousByte = 0
    private var previousLengthGroup = format.lengthPreviousGroups - 1

    override fun literal(value: Int) {
        encoder.encodeBit(selectorModel, runContext, 0)
        encodeLiteral(value, predictions[previousByte].unsignedInt)
        predictions[previousByte] = value.toByte()
        previousByte = value
        runContext = if (runContext < format.selectorRunBuckets) {
            minOf(runContext + 1, format.selectorRunBuckets - 1)
        } else {
            0
        }
    }

    override fun match(length: Int, distance: Int, lastByte: Int) {
        encoder.encodeBit(selectorModel, runContext, 1)
        encodeLength(length - config.minMatch)
        encodeDistance(distance)
        previousByte = lastByte
        runContext = if (runContext < format.selectorRunBuckets) {
            format.selectorRunBuckets
        } else {
            minOf(runContext + 1, 2 * format.selectorRunBuckets - 1)
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
    private fun matchRun(): Int = runContext - format.selectorRunBuckets + 1

    /**
     * Codes one byte through the order-1 context's matched-literal byte tree, using [predicted] as a
     * prior: matched sub-trees keyed by the predicted bit while the coded prefix still equals the
     * predicted byte's prefix, then the plain sub-tree once a bit diverges.
     *
     * @param value the literal to code, in `0..255`.
     * @param predicted the byte that last followed the current context, or `0` before that context
     * ever occurred.
     */
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
        previousLengthGroup = lengthPrevGroup(value, format.lengthPreviousGroups)
    }

    /**
     * Context of the range flag and of a short length: whether this match follows a match rather
     * than a literal, refined by the previous match's length group.
     */
    private fun lengthContext(): Int = longLengthContext() * format.lengthPreviousGroups + previousLengthGroup

    /**
     * Context of a long length: `1` when this match follows another match, `0` when it follows a
     * literal. The run counter still holds the pre-token state here.
     */
    private fun longLengthContext(): Int = if (runContext >= format.selectorRunBuckets) 1 else 0

    /**
     * Context of the rep bit: the match run preceding this match, capped at the last bucket, so a
     * match opening a run after literals is told apart from one continuing a run of matches.
     */
    private fun repContext(): Int = matchRun().coerceIn(0, format.repRunBuckets - 1)

    /**
     * Bucket of the rep index chain, the same preceding match run as [repContext] but capped at the
     * format's rep-index bucket count.
     */
    private fun repIndexContext(): Int = matchRun().coerceIn(0, format.repIndexRunBuckets - 1)

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
     * bits the high ones are equiprobable *raw* bits, while the format's low align bits are coded
     * adaptively with a per-position context, most significant first in each group.
     */
    private fun encodeFreshDistance(value: Int) {
        val slot = value.bitLength()
        encoder.encodeBitTree(distanceSlotModel, 0, slot, slotBits)
        // Slots 0 and 1 carry no mantissa at all, so nothing follows the slot for them.
        val mantissaBits = slot - 1
        val rawBits = mantissaBits - format.distanceAlignBits
        val alignBits = if (rawBits > 0) {
            encoder.encodeRawBits(value ushr format.distanceAlignBits, rawBits)
            format.distanceAlignBits
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
 * Default [Lz77Format.distanceAlignBits].
 */
internal const val DISTANCE_ALIGN_BITS = 4

/**
 * Default width of the *short* range a length field's lowest values are coded through, by the range
 * format's richly contextualized bit tree.
 */
internal const val SHORT_LENGTH_BITS = 4

/**
 * How many length values the short range holds, narrowed when the geometry is too small to fill the
 * width the format asks for.
 *
 * @param lengthBits the geometry's [Lz77Config.lengthBits].
 * @param shortLengthBits the format's [Lz77Format.shortLengthBits].
 */
internal fun shortLengthCount(lengthBits: Int, shortLengthBits: Int): Int =
    1 shl minOf(shortLengthBits, lengthBits)

/**
 * Default number of groups [lengthPrevGroup] folds a length field into, so that a length is coded in
 * the context of the previous match's length.
 */
internal const val LENGTH_PREV_GROUPS = 8

/**
 * Group a length field belongs to as the context of the *next* match's length: a logarithmic scale
 * widening as lengths grow, so that a rarely seen long length still shares statistics with its
 * neighbors.
 *
 * @param value a length field, `length - minMatch`.
 * @param groupCount how many groups the scale folds into. The scale has eight steps: a smaller count
 * merges the longest ones into its last group, a larger one leaves the groups past the eighth unused.
 */
internal fun lengthPrevGroup(value: Int, groupCount: Int = LENGTH_PREV_GROUPS): Int {
    val step = when {
        value < 8 -> value shr 1
        value < 12 -> 4
        value < 16 -> 5
        value < 24 -> 6
        else -> 7
    }
    return minOf(step, groupCount - 1)
}

/**
 * Default [Lz77Format.selectorRunBuckets].
 */
internal const val SELECTOR_RUN_BUCKETS = 16

/**
 * Default [Lz77Format.repRunBuckets].
 */
internal const val REP_RUN_BUCKETS = 3

/**
 * Default [Lz77Format.repIndexRunBuckets].
 */
internal const val REP_INDEX_RUN_BUCKETS = 4

/**
 * Default [Lz77Format.selectorModelAdaptShift].
 */
internal const val SELECTOR_MODEL_ADAPT_SHIFT = 5

/**
 * Default [Lz77Format.literalModelAdaptShift].
 */
internal const val LITERAL_MODEL_ADAPT_SHIFT = 5

/**
 * Default [Lz77Format.lengthModelAdaptShift].
 */
internal const val LENGTH_MODEL_ADAPT_SHIFT = 5

/**
 * Default [Lz77Format.repModelAdaptShift].
 */
internal const val REP_MODEL_ADAPT_SHIFT = 4

/**
 * Default [Lz77Format.distanceSlotModelAdaptShift].
 */
internal const val DISTANCE_SLOT_MODEL_ADAPT_SHIFT = 6

/**
 * Default [Lz77Format.distanceAlignModelAdaptShift].
 */
internal const val DISTANCE_ALIGN_MODEL_ADAPT_SHIFT = 7
