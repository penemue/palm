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
package com.github.penemue.palm.vlq

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VlqTest {

    @Test
    fun `long values round trip`() {
        val values = longArrayOf(0, 1, 127, 128, 255, 256, 16_383, 16_384, Int.MAX_VALUE.toLong(), Long.MAX_VALUE)
        val output = ByteArrayOutputStream()
        values.forEach { output.writeVarLong(it) }

        val input = ByteArrayInputStream(output.toByteArray())
        values.forEach { assertEquals(it, input.readVarLong()) }
        assertEquals(-1, input.read(), "stream should be fully consumed")
    }

    @Test
    fun `int values round trip`() {
        val values = intArrayOf(0, 1, 127, 128, 300, 65_535, Int.MAX_VALUE)
        val output = ByteArrayOutputStream()
        values.forEach { output.writeVarInt(it) }

        val input = ByteArrayInputStream(output.toByteArray())
        values.forEach { assertEquals(it, input.readVarInt()) }
    }

    @Test
    fun `small values use a single byte`() {
        val output = ByteArrayOutputStream()
        output.writeVarLong(0)
        output.writeVarInt(127)
        assertContentEquals(byteArrayOf(0x00, 0x7F), output.toByteArray())
    }

    @Test
    fun `least significant group first with continuation bit`() {
        val output = ByteArrayOutputStream()
        output.writeVarInt(300) // 0b1_0010_1100 -> groups 0101100, 0000010
        assertContentEquals(byteArrayOf(0xAC.toByte(), 0x02), output.toByteArray())
    }

    @Test
    fun `writers reject negative values`() {
        assertFailsWith<IllegalArgumentException> { ByteArrayOutputStream().writeVarLong(-1) }
        assertFailsWith<IllegalArgumentException> { ByteArrayOutputStream().writeVarInt(-1) }
    }

    @Test
    fun `readVarInt rejects values above Int range`() {
        val output = ByteArrayOutputStream()
        output.writeVarLong(Int.MAX_VALUE.toLong() + 1)
        val input = ByteArrayInputStream(output.toByteArray())
        assertFailsWith<IOException> { input.readVarInt() }
    }

    @Test
    fun `readVarLong rejects a truncated value`() {
        val input = ByteArrayInputStream(byteArrayOf(0x80.toByte())) // continuation set, no follow-up
        assertFailsWith<EOFException> { input.readVarLong() }
    }

    @Test
    fun `readVarLong rejects an overlong encoding`() {
        // Ten continuation bytes exceed the 64-bit budget.
        val overlong = ByteArray(10) { 0x80.toByte() }
        assertFailsWith<IOException> { ByteArrayInputStream(overlong).readVarLong() }
    }
}
