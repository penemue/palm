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

import java.io.IOException

/**
 * Reader of a coded next-byte-prediction token stream.
 *
 * Each implementation is the exact inverse of one [PalmistTokenConsumer] and must be paired with it.
 *
 * Implementations are **not** thread-safe.
 */
internal interface PalmistTokenReader {

    /**
     * Reads one literal token.
     *
     * @return the literal byte value in `0..255`.
     * @throws IOException if the stream ends before the literal could be read.
     */
    fun readLiteral(): Int
}
