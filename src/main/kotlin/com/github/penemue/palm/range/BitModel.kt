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
 * Number of fixed-point bits in an adaptive bit probability: probabilities live on a scale of
 * `2^PROBABILITY_BITS`. The smallest reachable probability is `2^(adaptShift - PROBABILITY_BITS)`.
 */
internal const val PROBABILITY_BITS = 13

/**
 * Total probability scale, `2^PROBABILITY_BITS`. A stored probability is the chance of a zero bit,
 * in `1 until PROBABILITY_TOTAL`.
 */
internal const val PROBABILITY_TOTAL = 1 shl PROBABILITY_BITS

/**
 * Default adaptation rate of a [BitModel], the classic LZMA value: each coded bit moves the active
 * probability `1 / 2^5` of the way toward itself.
 */
const val DEFAULT_PROBABILITY_ADAPT_SHIFT = 5

/**
 * Fastest adaptation a [BitModel] accepts, which is also the rate every context warms up from.
 */
const val MIN_PROBABILITY_ADAPT_SHIFT = 2

/**
 * Slowest adaptation a [BitModel] accepts.
 */
const val MAX_PROBABILITY_ADAPT_SHIFT = PROBABILITY_BITS - 1

/**
 * Renormalization threshold, `2^24`. The coder emits (encoder) or consumes (decoder) one byte
 * whenever the range would drop below it.
 */
internal const val RANGE_TOP = 1L shl 24

/**
 * One half of the [PROBABILITY_TOTAL] scale: a fresh bit is equally likely zero or one.
 */
private const val PROBABILITY_INITIAL = PROBABILITY_TOTAL / 2

/**
 * A bank of [size] independent adaptive bit probabilities, each the chance of a zero bit on a scale
 * of [PROBABILITY_TOTAL] and each starting at one half. The caller indexes the bank by context: an
 * index **is** a context (an order-1 byte, a bit-tree node, a flag, …).
 *
 * [RangeEncoder.encodeBit] and [RangeDecoder.decodeBit] read a probability and then adapt it toward
 * the bit just coded, so an encoder and a decoder that touch the same indices in the same order stay
 * in lock-step without transmitting the bank.
 *
 * Adaptation is an exponential moving average whose rate a context reaches by degrees: it starts at
 * `MIN_PROBABILITY_ADAPT_SHIFT` and moves one shift slower each time the context has seen `2^shift`
 * bits, until it settles at [adaptShift], which is therefore the slowest rate any context of this
 * model adapts at. A fresh context thus leaves one half within a few bits instead of crawling there
 * at the nominal rate.
 *
 * Instances are stateful and **not** thread-safe: one per coder, per stream.
 *
 * @param size number of independent adaptive bits (contexts) the model holds.
 * @property adaptShift adaptation rate, in `MIN_PROBABILITY_ADAPT_SHIFT..MAX_PROBABILITY_ADAPT_SHIFT`.
 */
class BitModel(size: Int, private val adaptShift: Int = DEFAULT_PROBABILITY_ADAPT_SHIFT) {

    private val probabilities = ShortArray(size) { PROBABILITY_INITIAL.toShort() }
    // Bits coded so far in each context, counted only while that count still picks the rate.
    private val observations = ShortArray(size)
    // The count from which a context adapts at the nominal rate, so counting past it changes nothing.
    private val warmUpBits = 1 shl (adaptShift - 1)

    init {
        require(adaptShift in MIN_PROBABILITY_ADAPT_SHIFT..MAX_PROBABILITY_ADAPT_SHIFT) {
            "adaptShift must be in $MIN_PROBABILITY_ADAPT_SHIFT..$MAX_PROBABILITY_ADAPT_SHIFT" +
                " but was $adaptShift"
        }
    }

    /**
     * A valid index is in `0 until size`.
     */
    internal val size: Int get() = probabilities.size

    /**
     * The current chance that the bit at [index] is zero, on the [PROBABILITY_TOTAL] scale and
     * always in `1 until PROBABILITY_TOTAL`.
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
            observed.bitLength().coerceAtLeast(MIN_PROBABILITY_ADAPT_SHIFT)
        } else {
            adaptShift
        }
        val probability = probabilities[index].toInt()
        probabilities[index] = if (bit == 0) {
            (probability + ((PROBABILITY_TOTAL - probability) ushr shift)).toShort()
        } else {
            (probability - (probability ushr shift)).toShort()
        }
    }
}
