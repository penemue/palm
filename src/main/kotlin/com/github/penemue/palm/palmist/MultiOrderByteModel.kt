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
package com.github.penemue.palm.palmist

import com.github.penemue.palm.util.MAX_SLOT_BITS
import com.github.penemue.palm.util.fibonacciHash
import com.github.penemue.palm.util.unsignedInt

/**
 * Independent next-byte predictors of order 1 upwards, presented as one list.
 *
 * Each order keeps its own table of predicted bytes, addressed by that many preceding bytes: orders 1
 * and 2 index their table directly, every deeper one folds its context through a Fibonacci
 * (multiplicative) hash and so shares slots between distinct contexts, in a table twice the size of
 * the order above it, since its context is that much wider. Every slot carries a confidence
 * raised by each byte its prediction gets right and spent to keep that prediction in place, so a
 * prediction confirmed several times survives a burst of outliers while an unconfirmed one is replaced
 * at once.
 *
 * The orders are read as one list of DISTINCT predictions, ordered by descending confidence: a byte
 * several orders agree on appears once, carrying the highest confidence any of them holds for it. The
 * list is never empty and is rewritten by every [learn] call.
 *
 * [RangePalmistTokenConsumer] and [RangePalmistTokenReader] each drive their own instance identically —
 * one [learn] call per coded byte — so their tables never diverge.
 *
 * This class is **not** thread-safe.
 *
 * @param orderCount how many orders predict in parallel, in
 * [MIN_ORDER_COUNT]`..`[MAX_ORDER_COUNT].
 */
internal class MultiOrderByteModel(private val orderCount: Int) {
    private val slotBits = IntArray(orderCount) { slotBitsOf(it) }
    private val tables = Array(orderCount) { GatedByteTable(slotBits[it]) }
    private val contextMasks = LongArray(orderCount) { contextMaskOf(it) }
    private val orderSlots = IntArray(orderCount)
    private val orderPredictions = IntArray(orderCount)
    private val orderConfidences = IntArray(orderCount)
    private val distinctPredictions = IntArray(orderCount)
    private val distinctConfidences = IntArray(orderCount)
    private var history = 0L

    /**
     * Number of distinct predicted bytes, in `1..orderCount`.
     */
    var count = 0
        private set

    init {
        refresh()
    }

    /**
     * The distinct predicted bytes, each in `0..255`, in the first [count] entries. The array is the
     * model's own and every [learn] call rewrites it.
     */
    fun predictions(): IntArray = distinctPredictions

    /**
     * Confidence backing the prediction at [index] of `0 until `[count], in `0..`[MAX_CONFIDENCE].
     */
    fun confidenceAt(index: Int): Int = distinctConfidences[index]

    /**
     * Records that [byte] (`0..255`) actually followed, teaching every order at the slot it predicted
     * from, and rolls the predictions forward to the next position.
     */
    fun learn(byte: Int) {
        for (i in 0 until orderCount) tables[i].learn(orderSlots[i], byte)
        history = (history shl Byte.SIZE_BITS) or byte.toLong()
        refresh()
    }

    private fun refresh() {
        for (i in 0 until orderCount) {
            val slot = slotOf(i)
            orderSlots[i] = slot
            orderPredictions[i] = tables[i].predictedByte(slot)
            orderConfidences[i] = tables[i].confidenceAt(slot)
        }
        collectDistinct()
        sortByConfidence()
    }

    /**
     * Deepest order first, so a byte several orders agree on takes the deepest one's place and every
     * later tie is resolved the same way.
     */
    private fun collectDistinct() {
        distinctPredictions[0] = orderPredictions[orderCount - 1]
        distinctConfidences[0] = orderConfidences[orderCount - 1]
        count = 1
        for (i in orderCount - 2 downTo 0) {
            val prediction = orderPredictions[i]
            var found = -1
            for (j in 0 until count) {
                if (distinctPredictions[j] == prediction) {
                    found = j
                    break
                }
            }
            if (found < 0) {
                distinctPredictions[count] = prediction
                distinctConfidences[count] = orderConfidences[i]
                count++
            } else {
                distinctConfidences[found] = maxOf(distinctConfidences[found], orderConfidences[i])
            }
        }
    }

    /**
     * Stable, so predictions of equal confidence keep the deeper order ahead.
     */
    private fun sortByConfidence() {
        for (i in 1 until count) {
            var swapped = false
            for (j in 0 until count - i) {
                // Strict, so a pair of equal confidences is left alone and the deeper order stays ahead.
                if (distinctConfidences[j] < distinctConfidences[j + 1]) {
                    val prediction = distinctPredictions[j]
                    distinctPredictions[j] = distinctPredictions[j + 1]
                    distinctPredictions[j + 1] = prediction
                    val confidence = distinctConfidences[j]
                    distinctConfidences[j] = distinctConfidences[j + 1]
                    distinctConfidences[j + 1] = confidence
                    swapped = true
                }
            }
            if (!swapped) {
                break
            }
        }
    }

    private fun slotOf(order: Int): Int {
        val context = history and contextMasks[order]
        return when (order) {
            DIRECT_ORDER_1, DIRECT_ORDER_2 -> context.toInt()
            else -> context.fibonacciHash(slotBits[order])
        }
    }

    private companion object {

        /**
         * Base-2 logarithm of the slot count of the table order [order] (zero-based) owns: the two
         * shallowest orders index theirs by the whole context, every deeper one hashes into a table
         * far smaller than its context and twice the size of the one above it.
         */
        fun slotBitsOf(order: Int) = when (order) {
            DIRECT_ORDER_1 -> ORDER_1_SLOT_BITS
            DIRECT_ORDER_2 -> ORDER_2_SLOT_BITS
            else -> FIRST_HASHED_SLOT_BITS + (order - FIRST_HASHED_ORDER)
        }

        /**
         * Mask keeping the bytes order [order] (zero-based) is keyed by, one more than its index.
         */
        fun contextMaskOf(order: Int): Long {
            val bits = (order + 1) * Byte.SIZE_BITS
            // A context exactly as wide as a Long needs no masking.
            return if (bits == Long.SIZE_BITS) -1L else (1L shl bits) - 1
        }
    }
}

/**
 * One order's prediction table: a byte per slot, plus a confidence occupying [CONFIDENCE_BITS] bits,
 * so [CONFIDENCE_SLOTS_PER_BYTE] slots share one byte of its array.
 *
 * A slot is non-negative and below the slot count, which is what lets the byte holding its confidence
 * be reached by a shift and its position inside that byte by a mask.
 *
 * @param slotBits base-2 logarithm of the slot count, in [CONFIDENCE_SLOT_BITS]`..`[MAX_SLOT_BITS]:
 * below that a slot count leaves the confidence array empty, above it [fibonacciHash] cannot address
 * the table.
 */
private class GatedByteTable(slotBits: Int) {
    init {
        require(slotBits in CONFIDENCE_SLOT_BITS..MAX_SLOT_BITS) {
            "slotBits must be in $CONFIDENCE_SLOT_BITS..$MAX_SLOT_BITS but was $slotBits"
        }
    }

    private val table = ByteArray(1 shl slotBits)
    private val confidence = ByteArray((1 shl slotBits) / CONFIDENCE_SLOTS_PER_BYTE)

    fun predictedByte(slot: Int): Int = table[slot].unsignedInt

    fun confidenceAt(slot: Int): Int =
        (confidence[slot ushr CONFIDENCE_SLOT_BITS].unsignedInt ushr shiftOf(slot)) and CONFIDENCE_MASK

    fun learn(slot: Int, byte: Int) {
        val index = slot ushr CONFIDENCE_SLOT_BITS
        val shift = shiftOf(slot)
        val packed = confidence[index].unsignedInt
        val credit = (packed ushr shift) and CONFIDENCE_MASK
        if (table[slot].unsignedInt == byte) {
            // The ceiling keeps the field below its mask, so neither step can carry into a
            // neighboring slot's field and the whole byte can be adjusted at once.
            if (credit < MAX_CONFIDENCE) {
                confidence[index] = (packed + (1 shl shift)).toByte()
            }
        } else if (credit == 0) {
            table[slot] = byte.toByte()
        } else {
            confidence[index] = (packed - (1 shl shift)).toByte()
        }
    }

    private fun shiftOf(slot: Int) = (slot and (CONFIDENCE_SLOTS_PER_BYTE - 1)) * CONFIDENCE_BITS
}

/**
 * How many contradicting bytes a fully confirmed prediction survives, which is also the value a slot's
 * confidence saturates at. Raising it past [CONFIDENCE_MASK] needs a wider field, or a step would
 * carry into the neighboring slot's.
 */
private const val MAX_CONFIDENCE = 2

/**
 * Values a slot's confidence takes, `0..`[MAX_CONFIDENCE].
 */
internal const val CONFIDENCE_STATES = MAX_CONFIDENCE + 1

/**
 * Base-2 logarithm of [CONFIDENCE_SLOTS_PER_BYTE], so the byte a slot's confidence shares is reached
 * by a shift. The packing is chosen here and the field width follows from it; past the base-2
 * logarithm of [Byte.SIZE_BITS] a field would have no bits left.
 */
private const val CONFIDENCE_SLOT_BITS = 2

/**
 * Slots sharing one byte of a table's confidence array.
 */
private const val CONFIDENCE_SLOTS_PER_BYTE = 1 shl CONFIDENCE_SLOT_BITS

/**
 * Width of one slot's confidence field, which is what [MAX_CONFIDENCE] must fit in.
 */
private const val CONFIDENCE_BITS = Byte.SIZE_BITS / CONFIDENCE_SLOTS_PER_BYTE

/**
 * Mask of a confidence field already shifted down to the bottom of its byte.
 */
private const val CONFIDENCE_MASK = (1 shl CONFIDENCE_BITS) - 1

/**
 * Zero-based index of order 1, whose table is indexed by the previous byte itself.
 */
private const val DIRECT_ORDER_1 = 0

/**
 * Zero-based index of order 2, whose table is indexed by the previous two bytes themselves.
 */
private const val DIRECT_ORDER_2 = 1

/**
 * As wide as the one byte keying [DIRECT_ORDER_1], so its table owns a slot per context.
 */
private const val ORDER_1_SLOT_BITS = 8

/**
 * As wide as the two bytes keying [DIRECT_ORDER_2], so its table owns a slot per context.
 */
private const val ORDER_2_SLOT_BITS = 16

/**
 * Zero-based index of order 3, the shallowest order whose context is wider than its table.
 */
private const val FIRST_HASHED_ORDER = DIRECT_ORDER_2 + 1

/**
 * Base-2 logarithm of the slot count of the shallowest hashed table.
 */
private const val FIRST_HASHED_SLOT_BITS = ORDER_2_SLOT_BITS + 1
