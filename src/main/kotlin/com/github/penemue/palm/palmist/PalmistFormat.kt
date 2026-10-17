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

import com.github.penemue.palm.range.DEFAULT_RANGE_FORMAT
import com.github.penemue.palm.range.MIN_LIVE_COUNT_BUCKETS
import com.github.penemue.palm.range.RangeFormat

/**
 * Parameters that define the Palmist stream model for one format version.
 *
 * None of them travel on the wire beyond [version], so a decoder must be given the very parameter set
 * its encoder used.
 *
 * @property version value written into the stream geometry to select this parameter set.
 * @property baseOrder order of the shallowest predictor, in `1..`[MAX_DIRECT_CONTEXT_BYTES], so that
 * it indexes its table by the whole context; a deeper base leaves fewer directly indexed tables.
 * @property range range-coder parameters used by the stream.
 * @property confidenceStates values a slot's confidence takes, `0` up to [maxConfidence], which is
 * also the confidence dimension of the chain model; [maxConfidence] has to fit the field
 * [confidenceSlotBits] leaves.
 * @property confidenceSlotBits base-2 logarithm of the number of confidence slots packed into one
 * byte, which fixes the width of a single slot's field.
 * @property shallowSlotBits base-2 logarithm of the slot count of the shallowest hashed table; every
 * deeper order doubles it.
 * @property chainModelAdaptShift rate the chain model settles at, tracking how likely each step's
 * prediction is to hold.
 * @property escapeModelAdaptShift rate the escape model settles at, tracking how the byte behind an
 * exhausted chain is distributed.
 * @property liveCountBuckets buckets the number of candidates still live at a coded bit is folded
 * into.
 * @property exhaustedHistoryStates states of the register recording which of the most recent literals
 * exhausted their chain, a power of two; the count fixes how far back it reaches.
 * @property runBuckets buckets the run of consecutive literals whose chain held is folded into.
 */
internal class PalmistFormat(
    val version: Int,
    val baseOrder: Int,
    val range: RangeFormat = DEFAULT_RANGE_FORMAT,
    val confidenceStates: Int = DEFAULT_CONFIDENCE_STATES,
    val confidenceSlotBits: Int = DEFAULT_CONFIDENCE_SLOT_BITS,
    val shallowSlotBits: Int = DEFAULT_SHALLOW_SLOT_BITS,
    val chainModelAdaptShift: Int = DEFAULT_CHAIN_MODEL_ADAPT_SHIFT,
    val escapeModelAdaptShift: Int = DEFAULT_ESCAPE_MODEL_ADAPT_SHIFT,
    val liveCountBuckets: Int = DEFAULT_LIVE_COUNT_BUCKETS,
    val exhaustedHistoryStates: Int = EXHAUSTED_HISTORY_STATES,
    val runBuckets: Int = RUN_BUCKETS,
) {
    init {
        require(baseOrder in 1..MAX_DIRECT_CONTEXT_BYTES) {
            "baseOrder must be in 1..$MAX_DIRECT_CONTEXT_BYTES but was $baseOrder"
        }
        require(confidenceStates >= 1) { "confidenceStates must be at least 1 but was $confidenceStates" }
        require(liveCountBuckets >= MIN_LIVE_COUNT_BUCKETS) {
            "liveCountBuckets must be at least $MIN_LIVE_COUNT_BUCKETS but was $liveCountBuckets"
        }
        require(exhaustedHistoryStates >= 1 && exhaustedHistoryStates.countOneBits() == 1) {
            "exhaustedHistoryStates must be a power of two but was $exhaustedHistoryStates"
        }
        require(runBuckets >= 1) { "runBuckets must be at least 1 but was $runBuckets" }
    }

    /**
     * Contradicting bytes a fully confirmed prediction survives, the value a slot's confidence
     * saturates at.
     */
    val maxConfidence: Int get() = confidenceStates - 1

    /**
     * Largest order count a stream of this version may record, which a reader admits even past
     * [maxOrderCount]: the deepest order reads [baseOrder]` + orderCount - 1` bytes and that context
     * has to fit the history.
     */
    val maxDecodableOrderCount: Int get() = MAX_CONTEXT_BYTES - baseOrder + 1

    /**
     * Largest order count a stream of this version may be written with.
     */
    val maxOrderCount: Int get() = minOf(MAX_ORDER_COUNT, maxDecodableOrderCount)
}


/**
 * Version of streams written before a geometry carried one.
 */
internal const val PALMIST_LEGACY_VERSION = 0

/**
 * Version recorded in every stream the compressor writes.
 */
internal const val PALMIST_CURRENT_VERSION = 1

/**
 * Leading geometry value announcing that a version follows it. A legacy geometry opens with its order
 * count, which is never below [MIN_ORDER_COUNT], so the two cannot be confused.
 */
internal const val GEOMETRY_VERSION_MARKER = 0

/**
 * Shallowest order the legacy ensemble predicts with, one byte of context.
 */
private const val LEGACY_BASE_ORDER = 1

/**
 * Default [PalmistFormat.confidenceStates].
 */
internal const val DEFAULT_CONFIDENCE_STATES = 3

/**
 * Default [PalmistFormat.confidenceSlotBits].
 */
private const val DEFAULT_CONFIDENCE_SLOT_BITS = 2

/**
 * Default [PalmistFormat.shallowSlotBits].
 */
private const val DEFAULT_SHALLOW_SLOT_BITS = 17

/**
 * Default [PalmistFormat.chainModelAdaptShift].
 */
private const val DEFAULT_CHAIN_MODEL_ADAPT_SHIFT = 5

/**
 * Default [PalmistFormat.escapeModelAdaptShift].
 */
private const val DEFAULT_ESCAPE_MODEL_ADAPT_SHIFT = 5

/**
 * Default [PalmistFormat.liveCountBuckets].
 */
private const val DEFAULT_LIVE_COUNT_BUCKETS = 2

/**
 * Parameter set of streams written before the geometry carried a version, reachable by decoding only.
 */
internal val PALMIST_LEGACY_FORMAT = PalmistFormat(PALMIST_LEGACY_VERSION, LEGACY_BASE_ORDER)

/**
 * Parameter set every stream the compressor writes is coded with.
 */
internal val PALMIST_CURRENT_FORMAT = PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER)

/**
 * Parameter set a stream is decoded with.
 *
 * @param version value read from the stream geometry.
 * @throws IllegalStateException when no parameter set is registered for [version].
 */
internal fun palmistFormat(version: Int): PalmistFormat = when (version) {
    PALMIST_LEGACY_VERSION -> PALMIST_LEGACY_FORMAT
    PALMIST_CURRENT_VERSION -> PALMIST_CURRENT_FORMAT
    else -> error("Unknown Palmist format version: $version")
}

/**
 * Rejects a geometry this version cannot code.
 *
 * @param config geometry the caller intends to compress with.
 * @throws IllegalArgumentException when [PalmistConfig.orderCount] exceeds [PalmistFormat.maxOrderCount].
 */
internal fun PalmistFormat.validate(config: PalmistConfig) {
    require(config.orderCount <= maxOrderCount) {
        "orderCount must be in $MIN_ORDER_COUNT..$maxOrderCount for format version $version"
    }
}
