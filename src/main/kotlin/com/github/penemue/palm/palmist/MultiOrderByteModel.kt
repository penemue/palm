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
 * Independent next-byte predictors of order [baseOrder] upwards, presented as one list.
 *
 * Each order keeps its own table of predicted bytes, addressed by that many preceding bytes: an order
 * reading at most two bytes indexes its table by the whole context, every deeper one folds its context
 * through a Fibonacci (multiplicative) hash and so shares slots between distinct contexts, in a table
 * twice the size of the order above it, since its context is that much wider. Every slot carries a
 * confidence raised by each byte its prediction gets right and spent to keep that prediction in place,
 * so a prediction confirmed several times survives a burst of outliers while an unconfirmed one is
 * replaced at once.
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
 * @param orderCount how many orders predict in parallel, at least [MIN_ORDER_COUNT] and bounded from
 * above with [baseOrder], since the deepest order's context has to fit the history; [MAX_ORDER_COUNT]
 * does not apply here.
 * @param baseOrder order of the shallowest predictor, bounded with [orderCount] so the deepest order
 * still fits the history; every order reading at most two bytes indexes its table directly, so a
 * deeper base leaves fewer such tables.
 * @param confidenceSlotBits base-2 logarithm of the number of packed confidence slots per byte.
 * @param maxConfidence largest confidence value stored in a slot.
 * @param shallowSlotBits base-2 logarithm of the slot count of the shallowest hashed table; every
 * deeper order doubles it.
 */
internal class MultiOrderByteModel(
    private val orderCount: Int,
    private val baseOrder: Int,
    private val confidenceSlotBits: Int,
    private val maxConfidence: Int,
    private val shallowSlotBits: Int,
) {
    init {
        require(orderCount >= MIN_ORDER_COUNT) {
            "orderCount must be at least $MIN_ORDER_COUNT but was $orderCount"
        }
        val deepestOrder = baseOrder + orderCount - 1
        require(deepestOrder <= MAX_CONTEXT_BYTES) {
            "the deepest of $orderCount orders from order $baseOrder reads $deepestOrder bytes," +
                " past the $MAX_CONTEXT_BYTES the history holds"
        }
    }

    private val directOrders = directOrderCount(baseOrder)
    private val slotBits = IntArray(orderCount) { slotBitsOf(it, baseOrder, shallowSlotBits) }
    private val tables = Array(orderCount) {
        GatedByteTable(slotBits[it], confidenceSlotBits, maxConfidence)
    }
    private val contextMasks = LongArray(orderCount) { contextMaskOf(it, baseOrder) }
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
     * Confidence backing the prediction at [index] of `0 until `[count], in `0..`[maxConfidence].
     */
    fun confidenceAt(index: Int): Int = distinctConfidences[index]

    /**
     * Records that [byte] (`0..255`) actually followed, teaching every order at the slot it predicted
     * from, and rolls the predictions forward to the next position.
     */
    fun learn(byte: Int) {
        for (i in 0 until orderCount) {
            tables[i].learn(orderSlots[i], orderPredictions[i], orderConfidences[i], byte)
        }
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
        return if (order < directOrders) context.toInt() else context.fibonacciHash(slotBits[order])
    }

    private companion object {

        /**
         * Base-2 logarithm of the slot count of the table order [order] (zero-based) owns: an order
         * reading at most [MAX_DIRECT_CONTEXT_BYTES] bytes indexes its table by the whole context,
         * every deeper one hashes into a table far smaller than its context and twice the size of the
         * one above it.
         */
        fun slotBitsOf(order: Int, baseOrder: Int, shallowSlotBits: Int) = when {
            order < directOrderCount(baseOrder) -> (baseOrder + order) * Byte.SIZE_BITS
            else -> shallowSlotBits + order - directOrderCount(baseOrder)
        }

        /**
         * Leading orders whose whole context addresses a slot with no hashing, namely those reading
         * at most [MAX_DIRECT_CONTEXT_BYTES] bytes.
         */
        fun directOrderCount(baseOrder: Int) = (MAX_DIRECT_CONTEXT_BYTES + 1 - baseOrder).coerceAtLeast(0)

        /**
         * Mask keeping the bytes order [order] (zero-based) is keyed by, [baseOrder] more than its
         * index.
         */
        fun contextMaskOf(order: Int, baseOrder: Int): Long {
            val bits = (order + baseOrder) * Byte.SIZE_BITS
            // A context exactly as wide as a Long needs no masking.
            return if (bits == Long.SIZE_BITS) -1L else (1L shl bits) - 1
        }
    }
}

/**
 * One order's prediction table: a byte per slot, plus a confidence field, several slots of which share
 * one byte of a second array.
 *
 * A slot is non-negative and below the slot count, which is what lets the byte holding its confidence
 * be reached by a shift and its position inside that byte by a mask.
 *
 * @param slotBits base-2 logarithm of the slot count, in [confidenceSlotBits]`..`[MAX_SLOT_BITS]:
 * below that a slot count leaves the confidence array empty, above it [fibonacciHash] cannot address
 * the table.
 * @param confidenceSlotBits base-2 logarithm of the confidence slots packed into one byte, which
 * fixes the width of a single slot's field.
 * @param maxConfidence value a slot's confidence saturates at; it has to fit the field
 * [confidenceSlotBits] leaves, or a step would carry into the neighboring slot's.
 */
private class GatedByteTable(
    slotBits: Int,
    private val confidenceSlotBits: Int,
    private val maxConfidence: Int,
) {
    private val confidenceSlotsPerByte = 1 shl confidenceSlotBits
    private val confidenceBits = Byte.SIZE_BITS / confidenceSlotsPerByte
    private val confidenceMask = (1 shl confidenceBits) - 1

    init {
        require(slotBits in confidenceSlotBits..MAX_SLOT_BITS) {
            "slotBits must be in $confidenceSlotBits..$MAX_SLOT_BITS but was $slotBits"
        }
        require(maxConfidence <= confidenceMask) {
            "maxConfidence must be at most $confidenceMask, the widest value a $confidenceBits-bit" +
                " confidence field holds, but was $maxConfidence"
        }
    }

    private val table = ByteArray(1 shl slotBits)
    private val confidence = ByteArray((1 shl slotBits) / confidenceSlotsPerByte)

    fun predictedByte(slot: Int): Int = table[slot].unsignedInt

    fun confidenceAt(slot: Int): Int =
        (confidence[slot ushr confidenceSlotBits].unsignedInt ushr shiftOf(slot)) and confidenceMask

    /**
     * Teaches [slot] that [byte] followed; [predicted] and [credit] must be the slot's current
     * [predictedByte] and [confidenceAt], which spares reading them again.
     */
    fun learn(slot: Int, predicted: Int, credit: Int, byte: Int) {
        if (predicted == byte) {
            // The ceiling keeps the field below its mask, so neither step can carry into a
            // neighboring slot's field and the whole byte can be adjusted at once.
            if (credit < maxConfidence) {
                val index = slot ushr confidenceSlotBits
                confidence[index] = (confidence[index] + (1 shl shiftOf(slot))).toByte()
            }
        } else if (credit == 0) {
            table[slot] = byte.toByte()
        } else {
            val index = slot ushr confidenceSlotBits
            confidence[index] = (confidence[index] - (1 shl shiftOf(slot))).toByte()
        }
    }

    private fun shiftOf(slot: Int) = (slot and (confidenceSlotsPerByte - 1)) * confidenceBits
}

/**
 * [PalmistFormat.baseOrder] of [PALMIST_CURRENT_FORMAT].
 */
internal const val BASE_ORDER = 2

/**
 * Deepest context any order may read, the bytes the `Long` rolling the history holds. An order asked
 * for more would key on a context wider than the register keeping it.
 */
internal const val MAX_CONTEXT_BYTES = Long.SIZE_BITS / Byte.SIZE_BITS

/**
 * Widest context an order indexes its table by directly, one slot per context; a wider one is hashed.
 */
internal const val MAX_DIRECT_CONTEXT_BYTES = 2

