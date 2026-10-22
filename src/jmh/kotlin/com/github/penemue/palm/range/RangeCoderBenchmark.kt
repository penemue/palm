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
package com.github.penemue.palm.range

import com.github.penemue.palm.corpus.canterburyBytes
import com.github.penemue.palm.util.unsignedInt
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Codes a real corpus file through [RangeEncoder] and [RangeDecoder] as order-1 literals: every byte
 * is one eight-bit bit tree keyed by the byte before it, the shape of a context-modelled literal
 * coder.
 *
 * The timed region is the coding loop alone. The streams, the coders and a fresh [BitModel] are
 * built before each invocation, so every invocation still starts from the cold contexts a real
 * stream starts with.
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
open class RangeCoderBenchmark {

    /**
     * Corpus file to code: English text, which the order-1 model predicts well, and dense binary,
     * which it does not.
     */
    @Param("alice29.txt", "kennedy.xls")
    var file: String = "alice29.txt"

    private lateinit var source: ByteArray
    private lateinit var encoded: ByteArray

    private lateinit var encoder: RangeEncoder
    private lateinit var encoderModel: BitModel
    private lateinit var decoder: RangeDecoder
    private lateinit var decoderModel: BitModel

    @Setup(Level.Trial)
    fun loadCorpus() {
        source = canterburyBytes(file)
        val output = ByteArrayOutputStream(source.size)
        encodeAll(RangeEncoder(output), literalModel())
        encoded = output.toByteArray()
    }

    @Setup(Level.Invocation)
    fun prepareCoders() {
        // Presized to the exact stream it will receive, so the output never grows while timed.
        encoder = RangeEncoder(ByteArrayOutputStream(encoded.size))
        encoderModel = literalModel()
        decoder = RangeDecoder(ByteArrayInputStream(encoded))
        decoderModel = literalModel()
    }

    @Benchmark
    fun encode() {
        encodeAll(encoder, encoderModel)
    }

    @Benchmark
    fun decode(bh: Blackhole) {
        val decoder = decoder
        val model = decoderModel
        var previous = 0
        repeat(source.size) {
            previous = decoder.decodeBitTree(model, previous shl Byte.SIZE_BITS, Byte.SIZE_BITS)
            bh.consume(previous)
        }
    }

    private fun encodeAll(encoder: RangeEncoder, model: BitModel) {
        var previous = 0
        for (byte in source) {
            val value = byte.unsignedInt
            encoder.encodeBitTree(model, previous shl Byte.SIZE_BITS, value, Byte.SIZE_BITS)
            previous = value
        }
        encoder.finish()
    }

    private fun literalModel() = BitModel(LITERAL_CONTEXTS shl Byte.SIZE_BITS)

    private companion object {
        /**
         * One bit tree per previous byte value.
         */
        const val LITERAL_CONTEXTS = 1 shl Byte.SIZE_BITS
    }
}
