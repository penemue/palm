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

import com.github.penemue.palm.CompressionProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PalmistTest {

    private fun roundTrip(data: ByteArray, config: PalmistConfig? = null) {
        val method = CompressionProvider.find(PALMIST_PROVIDER_ID).create()
        val packed = ByteArrayOutputStream()
        method.compress(ByteArrayInputStream(data), packed, config)
        val restored = ByteArrayOutputStream()
        // Decompression takes no config: the payload carries the geometry the decoder needs.
        method.decompress(ByteArrayInputStream(packed.toByteArray()), restored)
        assertContentEquals(data, restored.toByteArray(), "round trip mismatch for ${data.size} bytes")
    }

    private fun compressedSize(data: ByteArray): Int {
        val packed = ByteArrayOutputStream()
        CompressionProvider.find(PALMIST_PROVIDER_ID).create().compress(ByteArrayInputStream(data), packed)
        return packed.size()
    }

    // ---- prediction ------------------------------------------------------------------------------

    @Test
    fun `a fully predicted stretch costs a small fraction of a bit per byte`() {
        // Zero-filled tables predict 0 for every context, so all-zero input is matched by the very
        // first step of every chain.
        val size = 100_000
        val packed = compressedSize(ByteArray(size))
        assertTrue(packed * 8.0 / size < 0.1, "fully predicted input packed to $packed bytes")
    }

    @Test
    fun `an order-2 pattern is predicted once every order has learned it`() {
        val data = ByteArray(50_000) { if (it % 2 == 0) 'a'.code.toByte() else 'b'.code.toByte() }
        val packed = compressedSize(data)
        assertTrue(packed * 8.0 / data.size < 0.1, "periodic input packed to $packed bytes")
    }

    @Test
    fun `a pattern only a deep order can separate is still predicted`() {
        // In "abcabX" the order-2 context "ab" is followed by both 'c' and 'X', so orders 1 and 2
        // cannot resolve it alone; the deeper orders can, and the chain offers every order at once.
        val unit = "abcabX".toByteArray()
        val data = ByteArray(60_000) { unit[it % unit.size] }
        val packed = compressedSize(data)
        assertTrue(packed * 8.0 / data.size < 0.2, "deep-order pattern packed to $packed bytes")
    }

    // ---- round-trip coverage --------------------------------------------------------------------

    @Test
    fun `empty input`() = roundTrip(ByteArray(0))

    @Test
    fun `single byte`() = roundTrip(byteArrayOf(42))

    @Test
    fun `all identical bytes round trip`() = roundTrip(ByteArray(10_000) { 'A'.code.toByte() })

    @Test
    fun `bytes that coincide with the predicted zero byte round trip`() = roundTrip(ByteArray(5_000))

    @Test
    fun `input that begins with zeros round trips`() {
        roundTrip(ByteArray(10_000) { if (it < 6_000) 0 else (it % 251).toByte() })
    }

    @Test
    fun `repeating pattern`() {
        val unit = "abcabcabcXYZ".toByteArray()
        roundTrip(ByteArray(50_000) { unit[it % unit.size] })
    }

    @Test
    fun `text like data`() {
        val sentence = "the quick brown fox jumps over the lazy dog. ".toByteArray()
        val out = ByteArrayOutputStream()
        repeat(2_000) { out.write(sentence) }
        roundTrip(out.toByteArray())
    }

    @Test
    fun `incompressible random data still round trips`() {
        val data = ByteArray(20_000)
        Random(12345).nextBytes(data)
        roundTrip(data)
    }

    @Test
    fun `every byte value round trips`() {
        roundTrip(ByteArray(256) { it.toByte() })
    }

    @Test
    fun `various sizes round trip`() {
        val rnd = Random(99)
        for (size in intArrayOf(0, 1, 2, 3, 4, 5, 16, 17, 255, 256, 257, 1023, 4096, 65_537)) {
            val data = ByteArray(size)
            for (i in 0 until size) {
                data[i] = if (i % 3 == 0) rnd.nextInt(256).toByte() else (i % 7).toByte()
            }
            roundTrip(data)
        }
    }

    // ---- predictions -----------------------------------------------------------------------------

    @Test
    fun `predictions are distinct, confidence-ordered and never empty`() {
        val data = "the quick brown fox jumps over the lazy dog. ".toByteArray()
        for (orderCount in MIN_ORDER_COUNT..MAX_ORDER_COUNT) {
            val model = MultiOrderByteModel(orderCount)
            repeat(200) {
                for (byte in data) {
                    assertTrue(
                        model.count in 1..orderCount,
                        "prediction count ${model.count} out of range at orderCount $orderCount",
                    )
                    val seen = mutableSetOf<Int>()
                    val predictions = model.predictions()
                    for (index in 0 until model.count) {
                        assertTrue(predictions[index] in 0..255, "predicted byte out of range")
                        assertTrue(seen.add(predictions[index]), "duplicate predicted byte")
                        if (index > 0) {
                            assertTrue(
                                model.confidenceAt(index - 1) >= model.confidenceAt(index),
                                "predictions are not ordered by descending confidence",
                            )
                        }
                    }
                    model.learn(byte.toInt() and 0xFF)
                }
            }
        }
    }

    // ---- geometry and validation -----------------------------------------------------------------

    @Test
    fun `every supported order count round trips`() {
        val sentence = "the quick brown fox jumps over the lazy dog. ".toByteArray()
        val data = ByteArrayOutputStream().apply { repeat(500) { write(sentence) } }.toByteArray()
        for (orderCount in MIN_ORDER_COUNT..MAX_ORDER_COUNT) {
            roundTrip(data, PalmistConfig(orderCount = orderCount))
        }
    }

    @Test
    fun `a custom order count travels in the payload`() {
        val data = ByteArray(40_000) { (it % 251).toByte() }
        val method = CompressionProvider.find(PALMIST_PROVIDER_ID).create()

        fun compress(config: PalmistConfig?): ByteArray = ByteArrayOutputStream().also {
            method.compress(ByteArrayInputStream(data), it, config)
        }.toByteArray()

        val defaulted = compress(null)
        val deepest = compress(PalmistConfig(orderCount = MAX_ORDER_COUNT))
        assertTrue(!defaulted.contentEquals(deepest), "the two geometries must produce different payloads")
        // Both decode without a config, since each payload carries the geometry it was written with.
        for (packed in listOf(defaulted, deepest)) {
            val restored = ByteArrayOutputStream()
            method.decompress(ByteArrayInputStream(packed), restored)
            assertContentEquals(data, restored.toByteArray(), "round trip mismatch")
        }
    }

    @Test
    fun `an order count outside the supported range is rejected`() {
        assertFailsWith<IllegalArgumentException> { PalmistConfig(orderCount = MIN_ORDER_COUNT - 1) }
        assertFailsWith<IllegalArgumentException> { PalmistConfig(orderCount = MAX_ORDER_COUNT + 1) }
        PalmistConfig(orderCount = MIN_ORDER_COUNT) // the bounds themselves stay usable
        PalmistConfig(orderCount = MAX_ORDER_COUNT)
    }

    @Test
    fun `compress accepts a palmist config and rejects a config from another family`() {
        val method = CompressionProvider.find(PALMIST_PROVIDER_ID).create()
        val source = "repetitive repetitive repetitive".toByteArray()
        method.compress(ByteArrayInputStream(source), ByteArrayOutputStream(), PalmistConfig())
        val alien = object : com.github.penemue.palm.CompressionConfig {}
        assertFailsWith<IllegalArgumentException> {
            method.compress(ByteArrayInputStream(source), ByteArrayOutputStream(), alien)
        }
    }

    @Test
    fun `truncated payload is rejected`() {
        val method = CompressionProvider.find(PALMIST_PROVIDER_ID).create()
        val packed = ByteArrayOutputStream().also {
            method.compress(ByteArrayInputStream("repetitive repetitive repetitive".toByteArray()), it)
        }.toByteArray()
        assertFailsWith<IOException> {
            method.decompress(ByteArrayInputStream(packed.copyOf(packed.size - 1)), ByteArrayOutputStream())
        }
    }

    @Test
    fun `a payload of another family is rejected`() {
        val packed = ByteArrayOutputStream().also {
            CompressionProvider.find("lz77-greedy").create()
                .compress(ByteArrayInputStream("repetitive repetitive".toByteArray()), it)
        }.toByteArray()
        assertFailsWith<IOException> {
            CompressionProvider.find(PALMIST_PROVIDER_ID).create()
                .decompress(ByteArrayInputStream(packed), ByteArrayOutputStream())
        }
    }

    private companion object {
        const val PALMIST_PROVIDER_ID = "palmist"
    }
}
