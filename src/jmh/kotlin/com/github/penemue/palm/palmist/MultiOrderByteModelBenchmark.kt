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

import com.github.penemue.palm.corpus.canterburyBytes
import org.openjdk.jmh.annotations.Benchmark

import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Drives [MultiOrderByteModel] exactly as the palmist coders do — one [MultiOrderByteModel.learn] per
 * byte, reading the predictions before each one — over a real corpus file.
 *
 * A fresh model is built per invocation, so every measurement starts from the cold tables the model
 * actually starts a stream with, and the timed region covers the whole learn/predict cycle rather
 * than one loop inside it.
 *
 * The corpus is the standard benchmark set published at
 * [The Canterbury Corpus](https://corpus.canterbury.ac.nz/).
 *
 * @see <a href="https://github.com/openjdk/jmh">JMH</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = ["-Xms1g", "-Xmx1g"])
@State(Scope.Thread)
open class MultiOrderByteModelBenchmark {

    /**
     * Corpus file replayed through the model, picked to cover the two regimes the order ensemble
     * behaves differently on: English text and dense binary.
     */
    @Param("alice29.txt", "kennedy.xls")
    var file: String = "alice29.txt"

    /**
     * Orders predicting in parallel, since the per-byte cost grows with the count: a shipped-sized
     * ensemble and [MAX_ORDER_COUNT]. Counts near [MIN_ORDER_COUNT] are left out — their invocations
     * are the shortest and came out by far the noisiest.
     */
    @Param("5", "8")
    var orderCount: Int = DEFAULT_ORDER_COUNT

    private lateinit var source: ByteArray

    @Setup(Level.Trial)
    fun loadCorpus() {
        source = canterburyBytes(file)
    }

    /**
     * Replays the whole file, so a score is the model's cost for that file at that order count.
     */
    @Benchmark

    fun replay(bh: Blackhole) {
        val model = MultiOrderByteModel(orderCount)
        for (byte in source) {
            val value = byte.toInt() and 0xFF
            // The coders read the prediction list before learning the byte; consuming it here keeps
            // that read from being optimized away.
            val predictions = model.predictions()
            bh.consume(predictions[0])
            bh.consume(model.count)
            bh.consume(model.confidenceAt(0))
            model.learn(value)
        }
        bh.consume(model.count)
    }
}
