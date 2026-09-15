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

import com.github.penemue.palm.range.BitModel
import com.github.penemue.palm.range.RangeEncoder
import com.github.penemue.palm.range.encodeExcludedLiteral
import com.github.penemue.palm.range.excludedLiteralModel
import java.io.OutputStream

/**
 * Writes prediction tokens through an adaptive binary [RangeEncoder], so a literal costs its modeled
 * information content rather than a whole number of bits.
 *
 * A literal is coded as one unary chain over the predictions its own [MultiOrderByteModel] offers: a
 * bit per prediction, in their given order, answering whether it is the input byte. A set bit ends
 * the chain and the literal, and needs nothing further. When every prediction has been denied, the
 * byte itself follows as an escape, coded against all of them in an order-1 context, the previous
 * literal byte.
 *
 * Neither the prediction model nor the two range models travel on the wire; the paired
 * [RangePalmistTokenReader] rebuilds all three in lock-step.
 *
 * Calling [finish] flushes the range coder without closing the destination; no explicit end marker is
 * written.
 *
 * @param config geometry of the prediction model; the paired reader must be given the same one.
 */
class RangePalmistTokenConsumer(config: PalmistConfig, output: OutputStream) : PalmistTokenConsumer {
    private val encoder = RangeEncoder(output)
    private val model = MultiOrderByteModel(config.orderCount)
    private val state = ChainState(config.orderCount)
    private val chainModel = BitModel(state.chainModelSize)
    private val escapeModel = excludedLiteralModel(ESCAPE_CONTEXTS, ESCAPE_MODEL_ADAPT_SHIFT)

    override fun literal(value: Int) {
        val count = model.count
        val predictions = model.predictions()
        var position = -1
        for (index in 0 until count) {
            if (predictions[index] == value) {
                position = index
                break
            }
        }
        val context = state.chainContext(model)
        var index = 0
        while (index < count) {
            val bit = if (index == position) 1 else 0
            encoder.encodeBit(chainModel, state.chainIndex(context, index, model.confidenceAt(index)), bit)
            if (bit == 1) break
            index++
        }
        if (position < 0) {
            encoder.encodeExcludedLiteral(escapeModel, state.previousLiteral, value, predictions, count)
        }
        state.advance(value, exhausted = position < 0)
        model.learn(value)
    }

    override fun finish() {
        encoder.finish()
    }
}

/**
 * How many contexts the escape model keeps: one per value of the previous literal byte, so the model
 * for an escaped byte is chosen by the byte that precedes it.
 */
internal const val ESCAPE_CONTEXTS = 256

/**
 * Adaptation rate of the escape model.
 */
internal const val ESCAPE_MODEL_ADAPT_SHIFT = 5
