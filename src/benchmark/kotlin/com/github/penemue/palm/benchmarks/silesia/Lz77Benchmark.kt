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
package com.github.penemue.palm.benchmarks.silesia

import kotlin.test.Test

/**
 * Compression-ratio benchmarks of the LZ77 family over the complete Silesia Compression Corpus,
 * measured and reported by [runSilesiaBenchmark]. The tests differ only in the parser: greedy or
 * one-byte lazy.
 *
 * The corpus is the standard benchmark set published at
 * [The Silesia Compression Corpus](https://sun.aei.polsl.pl/~sdeor/index.php?page=silesia).
 */
class Lz77Benchmark {

    @Test
    fun `greedy LZ77 with range-coded tokens on Silesia Corpus`() =
        runSilesiaBenchmark("lz77-greedy")

    @Test
    fun `lazy LZ77 with range-coded tokens on Silesia Corpus`() =
        runSilesiaBenchmark("lz77-lazy")
}
