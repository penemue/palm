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

/**
 * Sink for a raw [LZ77](https://en.wikipedia.org/wiki/LZ77_and_LZ78) token stream: two kinds of
 * tokens — *literals* and back-reference *matches* — terminated by a [finish] marker.
 *
 * Tokens arrive in their **raw, un-encoded form**: a literal carries its plain byte value and a
 * match carries its plain `length` and `distance`.
 *
 * ### Contract
 * The methods are invoked in emission order: [literal] and [match] zero or more times, then
 * exactly one [finish]. After [finish] no further calls are made.
 *
 * Implementations are **not** required to be thread-safe; a consumer is driven from a single
 * thread.
 *
 * @property config configuration that defines the valid length and distance ranges of this token
 * stream.
 * @property repDistances the operation's single list of recent match distances, which a subclass
 * may query to name a repeated distance by its short index instead of spelling it out. The list is
 * *only queried* here; the driving loop advances it once per emitted match.
 */
abstract class Lz77TokenConsumer(
    val config: Lz77Config,
    protected val repDistances: RepDistances,
) {

    /**
     * Consumes a literal token: a single byte that was not encoded as part of a match.
     *
     * @param value the literal byte value, always in `0..255`.
     */
    abstract fun literal(value: Int)

    /**
     * Consumes a match token: a back-reference that reproduces [length] bytes copied from
     * [distance] bytes earlier in the already-decoded output.
     *
     * @param length number of bytes the match reproduces, in `config.minMatch..config.maxMatch`.
     * @param distance how far back the match starts, in `1..config.maxDistance`; `distance == 1` repeats
     * the previous byte, and a [distance] smaller than [length] denotes a legal overlapping copy.
     * @param lastByte the final byte this match reproduces, in `0..255`.
     */
    abstract fun match(length: Int, distance: Int, lastByte: Int)

    /**
     * Signals that the token stream is complete. A consumer that buffers should flush here.
     */
    abstract fun finish()
}
