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

import com.github.penemue.palm.CompressionConfig

/**
 * Fewest orders [PalmistConfig.orderCount] may ask for, leaving the directly indexed table and one
 * hashed one.
 */
const val MIN_ORDER_COUNT = 2

/**
 * Most orders [PalmistConfig.orderCount] may ask for, bounded by the `Long` holding the context: the
 * deepest order reads [Long.SIZE_BITS] bits of history.
 */
const val MAX_ORDER_COUNT = Long.SIZE_BITS / Byte.SIZE_BITS - (BASE_ORDER - 1)

/**
 * Default [PalmistConfig.orderCount].
 */
const val DEFAULT_ORDER_COUNT = 4

/**
 * Immutable geometry of the adaptive next-byte prediction compressor.
 *
 * The compressor offers every input byte to the bytes [orderCount] predictors of growing order expect
 * next, so the parameter sets how deep a context the deepest predictor reads. It also widens the token
 * stream: each extra order adds a step the coding chain may have to walk.
 *
 * @property orderCount how many orders predict in parallel, in [MIN_ORDER_COUNT]`..`[MAX_ORDER_COUNT];
 * the shallowest is order [BASE_ORDER] and order `n` is keyed by the `n` bytes preceding the position.
 * Only the shallowest order owns a table sized to its context; past it every added order doubles the
 * tables' memory.
 */
data class PalmistConfig(
    val orderCount: Int = DEFAULT_ORDER_COUNT,
) : CompressionConfig {
    init {
        require(orderCount in MIN_ORDER_COUNT..MAX_ORDER_COUNT) {
            "orderCount must be in $MIN_ORDER_COUNT..$MAX_ORDER_COUNT but was $orderCount"
        }
    }
}
