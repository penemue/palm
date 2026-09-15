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

/**
 * Coder state shared by [RangePalmistTokenConsumer] and [RangePalmistTokenReader]: everything the chain
 * model is conditioned on beyond the predictions themselves, and the addressing of that model.
 *
 * @param orderCount how many orders predict in parallel, which bounds both the prediction count a
 * context records and the number of steps a chain may take.
 */
internal class ChainState(orderCount: Int) {

    /**
     * Entries a chain context owns: one per step of the chain, keyed by that step's own confidence.
     */
    private val chainSteps = orderCount * CONFIDENCE_STATES

    /**
     * Byte that precedes the literal being coded, which keys the escape model.
     */
    var previousLiteral = 0
        private set

    private var exhaustedHistory = 0
    private var run = 0

    /**
     * Entries the chain model needs: one per step of every context, where a context is the
     * prediction count, the recent exhausted history, the highest confidence at this position and the
     * bucketed run of consecutive hits.
     */
    val chainModelSize = orderCount * EXHAUSTED_HISTORY_STATES * CONFIDENCE_STATES * RUN_BUCKETS * chainSteps

    /**
     * Chain model context for the coming literal.
     */
    fun chainContext(model: MultiOrderByteModel): Int =
        ((model.count - 1) * EXHAUSTED_HISTORY_STATES + exhaustedHistory) * CONFIDENCE_STATES * RUN_BUCKETS +
            model.confidenceAt(0) * RUN_BUCKETS + runBucket()

    /**
     * Index of the chain model entry a step uses, keyed by the confidence of the prediction that step
     * offers, so that a long-confirmed prediction and a freshly installed one are not modeled
     * together.
     */
    fun chainIndex(context: Int, step: Int, confidence: Int): Int =
        context * chainSteps + step * CONFIDENCE_STATES + confidence

    /**
     * Rolls the state past a literal of [value], whose chain ran out of predictions when [exhausted].
     */
    fun advance(value: Int, exhausted: Boolean) {
        run = if (exhausted) 0 else run + 1
        exhaustedHistory = ((exhaustedHistory shl 1) or (if (exhausted) 1 else 0)) and (EXHAUSTED_HISTORY_STATES - 1)
        previousLiteral = value
    }

    private fun runBucket(): Int = when {
        run <= 2 -> run
        run <= 4 -> 3
        run <= 8 -> 4
        else -> 5
    }
}

/**
 * States recording whether the chain of each of the two most recent literals ran out of predictions.
 */
private const val EXHAUSTED_HISTORY_STATES = 4

/**
 * Buckets the current run of literals whose chain did not run out is folded into.
 */
private const val RUN_BUCKETS = 6
