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
 * @param confidenceStates values a prediction's confidence takes, one chain model entry per step and
 * value.
 * @param exhaustedHistoryStates states the record of recently exhausted chains takes; it is kept by
 * shifting, so a count of `2^k` reaches the `k` most recent literals.
 * @param runBuckets buckets the run of literals whose chain held is folded into. The ladder has six
 * steps: fewer buckets merge its longest runs into the last bucket, more leave the buckets past the
 * sixth unused.
 */
internal class ChainState(
    orderCount: Int,
    private val confidenceStates: Int,
    private val exhaustedHistoryStates: Int,
    private val runBuckets: Int,
) {

    // Entries a chain context owns: one per step of the chain, keyed by that step's own confidence.
    private val chainSteps = orderCount * confidenceStates

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
    val chainModelSize = orderCount * exhaustedHistoryStates * confidenceStates * runBuckets * chainSteps

    /**
     * Chain model context for the coming literal.
     */
    fun chainContext(model: MultiOrderByteModel): Int =
        ((model.count - 1) * exhaustedHistoryStates + exhaustedHistory) * confidenceStates * runBuckets +
            model.confidenceAt(0) * runBuckets + runBucket()

    /**
     * Index of the chain model entry a step uses, keyed by the confidence of the prediction that step
     * offers, so that a long-confirmed prediction and a freshly installed one are not modeled
     * together.
     */
    fun chainIndex(context: Int, step: Int, confidence: Int): Int =
        context * chainSteps + step * confidenceStates + confidence

    /**
     * Rolls the state past a literal of [value], whose chain ran out of predictions when [exhausted].
     */
    fun advance(value: Int, exhausted: Boolean) {
        run = if (exhausted) 0 else run + 1
        exhaustedHistory = ((exhaustedHistory shl 1) or (if (exhausted) 1 else 0)) and (exhaustedHistoryStates - 1)
        previousLiteral = value
    }

    /**
     * Bucket of the current run, on a ladder that resolves the first runs one by one and widens as
     * they grow.
     */
    private fun runBucket(): Int {
        val step = when {
            run <= 2 -> run
            run <= 4 -> 3
            run <= 8 -> 4
            else -> 5
        }
        return minOf(step, runBuckets - 1)
    }
}

/**
 * Default [PalmistFormat.exhaustedHistoryStates].
 */
internal const val EXHAUSTED_HISTORY_STATES = 16

/**
 * Default [PalmistFormat.runBuckets].
 */
internal const val RUN_BUCKETS = 6
