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
package com.github.penemue.palm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class CompressionSpiTest {
    private val providers get() = CompressionProvider.load()

    @Test
    fun `ServiceLoader exposes every provider once and lookup finds each`() {
        val ids = providers.map { it.id }
        assertEquals(EXPECTED_IDS, ids.toSet())
        assertEquals(ids.size, ids.toSet().size)
        for (id in EXPECTED_IDS) assertEquals(id, CompressionProvider.find(id).id)
        assertFailsWith<IllegalArgumentException> { CompressionProvider.find("unknown") }
    }

    @Test
    fun `providers create independent methods`() {
        for (provider in providers) assertNotSame(provider.create(), provider.create(), provider.id)
    }

    @Test
    fun `all providers round trip focused inputs`() {
        val random = ByteArray(4_096).also { Random(2026).nextBytes(it) }
        val repetitive = ByteArray(20_000) { "PALM-palm-"[it % 10].code.toByte() }
        for (provider in providers) {
            for (source in listOf(ByteArray(0), random, repetitive)) {
                val packed = ByteArrayOutputStream()
                provider.create().compress(ByteArrayInputStream(source), packed)
                val restored = ByteArrayOutputStream()
                provider.create().decompress(ByteArrayInputStream(packed.toByteArray()), restored)
                assertContentEquals(source, restored.toByteArray(), provider.id)
            }
        }
    }

    @Test
    fun `methods do not close caller streams`() {
        for (provider in providers) {
            val input = CloseTrackingInputStream("PALMPALMPALM".toByteArray())
            val packed = CloseTrackingOutputStream()
            provider.create().compress(input, packed)
            assertEquals(false, input.closed, provider.id)
            assertEquals(false, packed.closed, provider.id)

            val compressedInput = CloseTrackingInputStream(packed.toByteArray())
            val restored = CloseTrackingOutputStream()
            provider.create().decompress(compressedInput, restored)
            assertEquals(false, compressedInput.closed, provider.id)
            assertEquals(false, restored.closed, provider.id)
        }
    }

    @Test
    fun `malformed and truncated payloads are rejected`() {
        for (provider in providers) {
            assertFailsWith<IOException>(provider.id) {
                provider.create().decompress(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream())
            }
            val packed = ByteArrayOutputStream().also {
                provider.create().compress(ByteArrayInputStream("repetitive repetitive repetitive".toByteArray()), it)
            }.toByteArray()
            assertFailsWith<IOException>(provider.id) {
                provider.create().decompress(ByteArrayInputStream(packed.copyOf(packed.size - 1)), ByteArrayOutputStream())
            }
            val badMagic = packed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertFailsWith<IOException>(provider.id) {
                provider.create().decompress(ByteArrayInputStream(badMagic), ByteArrayOutputStream())
            }
        }
    }

    private class CloseTrackingInputStream(data: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(data)
        var closed = false
            private set

        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {
            closed = true
        }
    }

    private class CloseTrackingOutputStream : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        var closed = false
            private set

        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()
        override fun close() {
            closed = true
        }
        fun toByteArray(): ByteArray = delegate.toByteArray()
    }

    private companion object {
        val EXPECTED_IDS = setOf(
            "lz77-greedy",
            "lz77-lazy",
            "palmist",
        )
    }
}
