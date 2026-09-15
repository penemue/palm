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

import com.github.penemue.palm.CompressionProvider
import com.github.penemue.palm.benchmarks.encode
import com.github.penemue.palm.corpus.CANTERBURY_CORPUS_SIZE
import com.github.penemue.palm.corpus.CANTERBURY_FILES
import com.github.penemue.palm.corpus.canterburyBytes
import java.util.Locale

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared compression-ratio harness for the complete Canterbury Corpus.
 *
 * Only encoded size is measured. Each file is loaded from test resources and compressed
 * independently, and is reported as raw input bytes, payload size, packed bits per input byte and
 * packed ratio; the payload size includes the framing header and the range coder's final flush. One
 * row is printed per file, followed by a corpus total.
 *
 * The corpus is the standard benchmark set published at
 * [The Canterbury Corpus](https://corpus.canterbury.ac.nz/).
 *
 * @param providerId stable SPI identifier of the compression provider used for every file.
 */
internal fun runCanterburyBenchmark(providerId: String) {
    val provider: CompressionProvider = CompressionProvider.find(providerId)
    var totalSourceBytes = 0L
    var totalEncodedBytes = 0L

    println("method: ${provider.id}")
    println("corpus: Canterbury Corpus")
    println("file\toriginal bytes\tencoded bytes\tpacked bits per byte\tpacked ratio")

    for (file in CANTERBURY_FILES) {
        val source = canterburyBytes(file)
        val encoded = encode(source, provider)
        val packedBitsPerByte = encoded.bytes * 8.0 / source.size
        val packedRatio = encoded.bytes.toDouble() / source.size

        println("$file\t${source.size}\t${encoded.bytes}\t${format(packedBitsPerByte)}\t${format(packedRatio)}")

        totalSourceBytes += source.size
        totalEncodedBytes += encoded.bytes
    }

    val totalPackedBitsPerByte = totalEncodedBytes * 8.0 / totalSourceBytes
    val totalPackedRatio = totalEncodedBytes.toDouble() / totalSourceBytes
    println("TOTAL\t$totalSourceBytes\t$totalEncodedBytes\t${format(totalPackedBitsPerByte)}\t${format(totalPackedRatio)}")

    assertEquals(CANTERBURY_CORPUS_SIZE, totalSourceBytes)
    assertTrue(totalEncodedBytes > 0)
}

/**
 * Locale-independent, so build logs stay comparable.
 */
private fun format(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
