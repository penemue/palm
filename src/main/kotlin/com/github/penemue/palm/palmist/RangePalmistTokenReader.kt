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

import com.github.penemue.palm.range.RangeDecoder
import com.github.penemue.palm.range.decodeExcludedLiteral
import com.github.penemue.palm.range.excludedLiteralModel
import java.io.InputStream

/**
 * Inverse of [RangePalmistTokenConsumer]: walks the same unary chain over the predictions its own
 * [MultiOrderByteModel] offers and, when the chain runs out, reads the byte itself.
 *
 * The prediction model, the model layouts, the contexts and the adaptation match the consumer exactly,
 * so all three models stay in lock-step without anything about them traveling on the wire.
 *
 * @param orderCount prediction orders recorded in the stream geometry, in
 * [MIN_ORDER_COUNT]`..`[PalmistFormat.maxDecodableOrderCount] of [format].
 * @param input source of the range-coded stream; the reader does not close it.
 * @param format parameter set that defines the model layout and range coder for the stream.
 */
internal class RangePalmistTokenReader(
    orderCount: Int,
    input: InputStream,
    private val format: PalmistFormat = PALMIST_CURRENT_FORMAT,
) : PalmistTokenReader {
    private val decoder = RangeDecoder(input, format.range)
    private val model = MultiOrderByteModel(
        orderCount,
        format.baseOrder,
        format.confidenceSlotBits,
        format.maxConfidence,
        format.shallowSlotBits,
    )
    private val state = ChainState(
        orderCount,
        format.confidenceStates,
        format.exhaustedHistoryStates,
        format.runBuckets,
    )
    private val chainModel = BitModel(state.chainModelSize, format.chainModelAdaptShift, format.range)
    private val escapeModel = excludedLiteralModel(
        ESCAPE_CONTEXTS,
        format.escapeModelAdaptShift,
        format.liveCountBuckets,
        format.range,
    )

    override fun readLiteral(): Int {
        val count = model.count
        val predictions = model.predictions()
        val context = state.chainContext(model)
        var index = 0
        var value = -1
        while (index < count) {
            if (decoder.decodeBit(chainModel, state.chainIndex(context, index, model.confidenceAt(index))) == 1) {
                value = predictions[index]
                break
            }
            index++
        }
        val exhausted = value < 0
        if (exhausted) {
            value = decoder.decodeExcludedLiteral(
                escapeModel,
                state.previousLiteral,
                predictions,
                count,
                format.liveCountBuckets,
            )
        }
        state.advance(value, exhausted)
        model.learn(value)
        return value
    }
}
