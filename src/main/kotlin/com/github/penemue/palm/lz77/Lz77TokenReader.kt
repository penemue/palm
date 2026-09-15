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
package com.github.penemue.palm.lz77

import java.io.IOException

/**
 * Reader of a coded [LZ77](https://en.wikipedia.org/wiki/LZ77_and_LZ78) token stream: the selector
 * bit, the literal payload, and the length and distance fields.
 *
 * Each implementation is the exact inverse of one [Lz77TokenConsumer] and must be paired with it.
 *
 * Implementations are **not** thread-safe.
 *
 * @property repDistances the operation's single list of recent match distances, which a subclass
 * may query to resolve a distance the stream named by its short index. The list is *only queried*
 * here; the decode loop advances it once per decoded match.
 */
internal abstract class Lz77TokenReader(protected val repDistances: RepDistances) {

    /**
     * Reads the selector that opens the next token and returns `true` for a match, `false` for a
     * literal.
     *
     * @throws IOException if the stream ends before a token could be started.
     */
    abstract fun readIsMatch(): Boolean

    /**
     * Reads a literal byte value in `0..255`. Called only after [readIsMatch] returned `false`.
     *
     * @param previousByte the byte preceding this literal in the reconstructed output, or `0` when
     * the literal opens the stream.
     */
    abstract fun readLiteral(previousByte: Int): Int

    /**
     * Reads a match length field and returns the plain match `length`, within the paired consumer's
     * `minMatch..maxMatch` range. Called only after [readIsMatch] returned `true`.
     */
    abstract fun readLength(): Int

    /**
     * Reads a match distance field and returns the plain match `distance`, in `1..maxDistance` of
     * the stream's geometry. Called immediately after [readLength] for the same match.
     */
    abstract fun readDistance(): Int
}
