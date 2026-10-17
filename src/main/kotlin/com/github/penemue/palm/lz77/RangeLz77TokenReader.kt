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
import com.github.penemue.palm.range.RangeDecoder
import com.github.penemue.palm.range.decodeMatchedBitTree
import com.github.penemue.palm.util.bitLength
import com.github.penemue.palm.util.unsignedInt
import java.io.InputStream

/**
 * Inverse of [RangeLz77TokenConsumer]: reconstructs each token from an adaptive binary
 * [RangeDecoder], rebuilding the same models (run-bucketed selector, matched-literal order-1
 * literal, range-split length, rep bit and index, distance slot) and the same prediction table in
 * lock-step so nothing about them travels on the wire.
 *
 * The model layout, contexts and adaptation must match the consumer exactly. The run counter
 * advances only at the *end* of each token, so the selector, the length field and the rep bit of a
 * match all read the same pre-token run state.
 *
 * The list of remembered distances belongs to the decompression operation and is advanced by the
 * decode loop once per decoded match; this reader only queries it.
 *
 * @param config LZ77 geometry that bounds the length and distance fields.
 * @param input source of the range-coded stream; the reader does not close it.
 * @param repDistances the operation's list of recent match distances a rep hit resolves against.
 * @param format parameter set that defines the token models and range coder for the stream.
 */
internal class RangeLz77TokenReader(
    private val config: Lz77Config,
    input: InputStream,
    repDistances: RepDistances,
    private val format: Lz77Format = LZ77_CURRENT_FORMAT,
) : Lz77TokenReader(repDistances) {
    private val decoder = RangeDecoder(input, format.range)
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
    private var previousLengthGroup = format.lengthPreviousGroups - 1

    override fun readIsMatch(): Boolean =
        decoder.decodeBit(selectorModel, runContext) == 1

    override fun readLiteral(previousByte: Int): Int {
        val value = decodeLiteral(previousByte, predictions[previousByte].unsignedInt)
        predictions[previousByte] = value.toByte()
        runContext = if (runContext < format.selectorRunBuckets) {
            minOf(runContext + 1, format.selectorRunBuckets - 1)
        } else {
            0
        }
        return value
    }

    override fun readLength(): Int {
        val context = lengthContext()
        val isLong = decoder.decodeBit(lengthRangeModel, context) == 1

        val value = if (isLong) {
            shortLengthCount +
                    decoder.decodeBitTree(longLengthModel, longLengthContext() shl lengthBits, lengthBits)
        } else {
            decoder.decodeBitTree(shortLengthModel, context shl shortLengthBits, shortLengthBits)
        }
        previousLengthGroup = lengthPrevGroup(value, format.lengthPreviousGroups)
        return config.minMatch + value
    }

    override fun readDistance(): Int {
        val distance = if (repDistances.size == 0 || decoder.decodeBit(repModel, repContext()) == 0) {
            decodeDistance() + 1
        } else {
            repDistances[decodeRepIndex()]
        }
        runContext = if (runContext < format.selectorRunBuckets) {
            format.selectorRunBuckets
        } else {
            minOf(runContext + 1, 2 * format.selectorRunBuckets - 1)
        }
        return distance
    }

    /**
     * Decodes one byte (`0..255`) from the order-1 context's matched-literal byte tree, using
     * [predicted] as a prior: matched sub-trees keyed by the predicted bit while the decoded prefix
     * still equals the predicted byte's prefix, then the plain sub-tree once a bit diverges.
     *
     * @param previousByte the order-1 context this literal is coded in.
     * @param predicted the byte that last followed [previousByte], or `0` before that context ever
     * occurred.
     */
    private fun decodeLiteral(previousByte: Int, predicted: Int): Int =
        decoder.decodeMatchedBitTree(literalModel, previousByte, predicted)

    /**
     * Capped length of the match run preceding this match, mirroring the consumer, or a non-positive
     * value after a literal. [repContext] and [repIndexContext] clamp both ends, which is what folds
     * every literal case into their first bucket.
     */
    private fun matchRun(): Int = runContext - format.selectorRunBuckets + 1

    /**
     * Context of the range flag and of a short length, mirroring the consumer: whether this match
     * follows a match rather than a literal, refined by the previous match's length group.
     */
    private fun lengthContext(): Int = longLengthContext() * format.lengthPreviousGroups + previousLengthGroup

    /**
     * Context of a long length, mirroring the consumer: `1` when this match follows another match,
     * `0` when it follows a literal.
     */
    private fun longLengthContext(): Int = if (runContext >= format.selectorRunBuckets) 1 else 0

    /**
     * Reads a rep index from its unary chain of adaptive bits: a `0` stops at the current position,
     * a `1` moves on. Reaching the last position needs no bit, since every earlier one was rejected.
     */
    private fun decodeRepIndex(): Int {
        val base = repIndexContext() * repIndexPositions
        for (i in 0 until repDistances.size - 1) {
            if (decoder.decodeBit(repIndexModel, base + i) == 0) {
                return i
            }
        }
        return repDistances.size - 1
    }

    /**
     * Context of the rep bit, mirroring the consumer: the match run preceding this match, capped at
     * the last bucket.
     */
    private fun repContext(): Int = matchRun().coerceIn(0, format.repRunBuckets - 1)

    /**
     * Bucket of the rep index chain, mirroring the consumer: the same preceding match run capped at
     * the format's rep-index bucket count.
     */
    private fun repIndexContext(): Int = matchRun().coerceIn(0, format.repIndexRunBuckets - 1)

    /**
     * Decodes a zero-based `distance - 1`: an adaptive slot (significant-bit count), then the high
     * mantissa bits as equiprobable raw bits and the format's low align bits from their per-position
     * adaptive contexts, the exact inverse of the consumer's writer.
     */
    private fun decodeDistance(): Int {
        val slot = decoder.decodeBitTree(distanceSlotModel, 0, slotBits)
        // The leading one implied by the slot is (1 shl slot) ushr 1. Slots 0 and 1 carry no
        // mantissa, so nothing follows the slot for them and the result is that leading one alone.
        val mantissaBits = slot - 1
        val rawBits = mantissaBits - format.distanceAlignBits
        var high = 0
        val alignBits = if (rawBits > 0) {
            high = decoder.decodeRawBits(rawBits)
            format.distanceAlignBits
        } else {
            mantissaBits
        }
        var low = 0
        for (bit in alignBits - 1 downTo 0) {
            low = low or (decoder.decodeBit(distanceAlignModel, bit) shl bit)
        }
        return ((1 shl slot) ushr 1) or (high shl format.distanceAlignBits) or low
    }
}
