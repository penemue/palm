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
 * Sink for the raw next-byte-prediction token stream.
 *
 * The stream has a single kind of token: one *literal* per input byte, carrying the plain byte value.
 *
 * ### Contract
 *
 * [literal] is invoked once per input byte, in input order, then exactly one [finish]. After [finish]
 * no further calls are made. Empty input emits no literal at all, just [finish].
 *
 * Implementations are **not** required to be thread-safe; a consumer is driven from a single thread.
 */
interface PalmistTokenConsumer {

    /**
     * Consumes one literal token.
     *
     * @param value the input byte, in `0..255`.
     */
    fun literal(value: Int)

    /**
     * Signals that the token stream is complete. A consumer that buffers should flush here.
     */
    fun finish()
}
