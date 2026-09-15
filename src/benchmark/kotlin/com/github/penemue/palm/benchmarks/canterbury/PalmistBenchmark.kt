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
package com.github.penemue.palm.benchmarks.canterbury

import kotlin.test.Test

/**
 * Compression-ratio benchmark of the next-byte prediction compressor over the complete Canterbury
 * Corpus, measured and reported by [runCanterburyBenchmark].
 *
 * The corpus is the standard benchmark set published at
 * [The Canterbury Corpus](https://corpus.canterbury.ac.nz/).
 */
class PalmistBenchmark {

    @Test
    fun `prediction by several orders at once on Canterbury Corpus`() =
        runCanterburyBenchmark("palmist")
}
