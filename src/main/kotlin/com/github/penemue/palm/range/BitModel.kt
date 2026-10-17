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

import com.github.penemue.palm.util.bitLength

/**
 * Default number of fixed-point bits in an adaptive bit probability, the precision a model runs at
 * unless its [RangeFormat] specifies another. The smallest reachable probability is
 * `2^(adaptShift - probabilityBits)`.
 */
internal const val PROBABILITY_BITS = 13

/**
 * Range-coder parameters that affect the adaptive probability model and renormalization. A decoder
 * must be given the values its encoder used, otherwise the two leave lock-step.
 *
 * @property probabilityBits number of fixed-point probability bits, in
 * [MIN_PROBABILITY_BITS]`..`[MAX_PROBABILITY_BITS].
 * @property rangeTop minimum range before renormalization, in `2^probabilityBits..`[MAX_RANGE_TOP]:
 * below that a range no longer splits into two non-empty parts at every probability.
 * @property warmUpAdaptShift rate every context of a [BitModel] starts adapting at, in
 * [MIN_PROBABILITY_ADAPT_SHIFT] up to one shift below [probabilityBits]; it is also the fastest rate a
 * model on this format accepts.
 */
class RangeFormat(
    val probabilityBits: Int = PROBABILITY_BITS,
    val rangeTop: Long = RANGE_TOP,
    val warmUpAdaptShift: Int = WARM_UP_ADAPT_SHIFT,
) {
    init {
        require(probabilityBits in MIN_PROBABILITY_BITS..MAX_PROBABILITY_BITS) {
            "probabilityBits must be in $MIN_PROBABILITY_BITS..$MAX_PROBABILITY_BITS" +
                " but was $probabilityBits"
        }
        val minRangeTop = 1L shl probabilityBits
        require(rangeTop in minRangeTop..MAX_RANGE_TOP) {
            "rangeTop must be in $minRangeTop..$MAX_RANGE_TOP for $probabilityBits probability bits" +
                " but was $rangeTop"
        }
        val maxWarmUpAdaptShift = probabilityBits - 1
        require(warmUpAdaptShift in MIN_PROBABILITY_ADAPT_SHIFT..maxWarmUpAdaptShift) {
            "warmUpAdaptShift must be in $MIN_PROBABILITY_ADAPT_SHIFT..$maxWarmUpAdaptShift for" +
                " $probabilityBits probability bits but was $warmUpAdaptShift"
        }
    }
}

/**
 * The parameters every coder and model runs on unless a stream's geometry pins another set.
 */
internal val DEFAULT_RANGE_FORMAT = RangeFormat()

/**
 * Total probability scale of [DEFAULT_RANGE_FORMAT]. A stored probability is the chance of a zero
 * bit, strictly inside the scale of the format its model runs on.
 */
internal const val PROBABILITY_TOTAL = 1 shl PROBABILITY_BITS

/**
 * Default adaptation rate of a [BitModel], the classic LZMA value: each coded bit moves the active
 * probability `1 / 2^5` of the way toward itself.
 */
const val DEFAULT_PROBABILITY_ADAPT_SHIFT = 5

/**
 * Fastest adaptation a [RangeFormat] may start its contexts at, and so the fastest rate any
 * [BitModel] adapts at.
 */
const val MIN_PROBABILITY_ADAPT_SHIFT = 2

/**
 * Default [RangeFormat.warmUpAdaptShift].
 */
internal const val WARM_UP_ADAPT_SHIFT = 2

/**
 * Slowest adaptation a [BitModel] on [DEFAULT_RANGE_FORMAT] accepts. A model bounds its rate by the
 * precision it runs at: one shift slower than that, and a probability sitting at one half would no
 * longer move at all.
 */
const val MAX_PROBABILITY_ADAPT_SHIFT = PROBABILITY_BITS - 1

/**
 * Coarsest probability precision a [RangeFormat] accepts, the narrowest scale that still leaves room
 * for [MIN_PROBABILITY_ADAPT_SHIFT].
 */
const val MIN_PROBABILITY_BITS = MIN_PROBABILITY_ADAPT_SHIFT + 1

/**
 * Finest probability precision a [RangeFormat] accepts: a probability is held in a signed 16-bit
 * slot, which a wider scale would overflow.
 */
const val MAX_PROBABILITY_BITS = Short.SIZE_BITS - 1

/**
 * Largest renormalization threshold a [RangeFormat] accepts. Renormalization shifts the 32-bit range
 * up by one byte, so a range just below a wider threshold would overflow it.
 */
const val MAX_RANGE_TOP = 1L shl (Int.SIZE_BITS - Byte.SIZE_BITS)

/**
 * Default renormalization threshold. The coder emits (encoder) or consumes (decoder) one byte
 * whenever the range would drop below the threshold of the format it runs on.
 */
internal const val RANGE_TOP = MAX_RANGE_TOP

/**
 * [size] independent adaptive bit probabilities, each the chance of a zero bit on the scale of its
 * [format] and each starting at one half. The caller indexes them by context: an index **is** a
 * context (an order-1 byte, a bit-tree node, a flag, …).
 *
 * [RangeEncoder.encodeBit] and [RangeDecoder.decodeBit] read a probability and then adapt it toward
 * the bit just coded, so an encoder and a decoder that touch the same indices in the same order stay
 * in lock-step without transmitting the probabilities.
 *
 * Adaptation is an exponential moving average whose rate a context reaches by degrees: it starts at
 * the warm-up rate of [format] and moves one shift slower each time the context has seen `2^shift`
 * bits, until it settles at [adaptShift], which is therefore the slowest rate any context of this
 * model adapts at. A fresh context thus leaves one half within a few bits instead of crawling there
 * at the nominal rate.
 *
 * Instances are stateful and **not** thread-safe: one per coder, per stream.
 *
 * @param size number of independent adaptive bits (contexts) the model holds.
 * @property adaptShift adaptation rate, from the warm-up rate of [format] up to one shift below its
 * probability bits.
 * @property format probability precision and warm-up rate used by the model.
 */
class BitModel(
    size: Int,
    private val adaptShift: Int,
    private val format: RangeFormat,
) {

    /**
     * A model on the default [RangeFormat].
     */
    constructor(size: Int, adaptShift: Int = DEFAULT_PROBABILITY_ADAPT_SHIFT) :
        this(size, adaptShift, DEFAULT_RANGE_FORMAT)

    private val probabilityTotal = 1 shl format.probabilityBits
    private val probabilities = ShortArray(size) { (probabilityTotal / 2).toShort() }
    // Bits coded so far in each context, counted only while that count still picks the rate.
    private val observations = ShortArray(size)
    // The count from which a context adapts at the nominal rate, so counting past it changes nothing.
    private val warmUpBits = 1 shl (adaptShift - 1)
    private val warmUpAdaptShift = format.warmUpAdaptShift

    init {
        val maxAdaptShift = format.probabilityBits - 1
        require(adaptShift in warmUpAdaptShift..maxAdaptShift) {
            "adaptShift must be in $warmUpAdaptShift..$maxAdaptShift" +
                " but was $adaptShift"
        }
    }

    /**
     * A valid index is in `0 until size`.
     */
    internal val size: Int get() = probabilities.size

    /**
     * The current chance that the bit at [index] is zero, on the scale of this model's [format] and
     * always strictly inside it.
     */
    internal fun probabilityOfZero(index: Int): Int = probabilities[index].toInt()

    /**
     * Moves the probability at [index] one step toward the [bit] just coded, at the rate that
     * context has reached.
     */
    internal fun adapt(index: Int, bit: Int) {
        val observed = observations[index].toInt()
        val shift = if (observed < warmUpBits) {
            observations[index] = (observed + 1).toShort()
            // The count's bit length is the shift it has earned: it grows by one each time the count doubles.
            observed.bitLength().coerceAtLeast(warmUpAdaptShift)
        } else {
            adaptShift
        }
        val probability = probabilities[index].toInt()
        probabilities[index] = if (bit == 0) {
            (probability + ((probabilityTotal - probability) ushr shift)).toShort()
        } else {
            (probability - (probability ushr shift)).toShort()
        }
    }
}
