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

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Payload bits one VLQ byte carries; the eighth, highest bit flags a further byte.
 */
private const val PAYLOAD_BITS = 7

/**
 * Selects the payload bits of a VLQ byte.
 */
private const val PAYLOAD_MASK = 0x7F

/**
 * Set in every VLQ byte but the last.
 */
private const val CONTINUATION_BIT = 0x80

/**
 * Writes a non-negative [Long] as an unsigned little-endian base-128
 * [variable-length quantity](https://en.wikipedia.org/wiki/LEB128) (LEB128).
 *
 * The value is split into 7-bit groups emitted least-significant group first. Every byte but the
 * last carries a set continuation bit, which delimits the value. `0` takes one byte and any value
 * takes at most nine.
 *
 * @throws IllegalArgumentException if [value] is negative.
 * @see readVarLong
 */
fun OutputStream.writeVarLong(value: Long) {
    require(value >= 0) { "VLQ value must be non-negative but was $value" }
    var remaining = value
    while (true) {
        val group = (remaining and PAYLOAD_MASK.toLong()).toInt()
        remaining = remaining ushr PAYLOAD_BITS
        if (remaining == 0L) {
            write(group)
            return
        }
        write(group or CONTINUATION_BIT)
    }
}

/**
 * Writes a non-negative [Int] as an unsigned LEB128
 * [variable-length quantity](https://en.wikipedia.org/wiki/LEB128); see [writeVarLong] for the format.
 *
 * @throws IllegalArgumentException if [value] is negative.
 * @see readVarInt
 */
fun OutputStream.writeVarInt(value: Int) = writeVarLong(value.toLong())

/**
 * Reads a non-negative [Long] written by [writeVarLong].
 *
 * @throws EOFException if the stream ends before the value's final byte.
 * @throws IOException if the encoding spans more than ten payload groups or overflows a signed
 * [Long].
 * @see writeVarLong
 */
fun InputStream.readVarLong(): Long {
    var result = 0L
    var shift = 0
    while (true) {
        if (shift >= Long.SIZE_BITS) throw IOException("VLQ value exceeds 64 bits")
        val byte = read()
        if (byte == -1) throw EOFException("Truncated VLQ value")
        result = result or ((byte.toLong() and PAYLOAD_MASK.toLong()) shl shift)
        if (byte and CONTINUATION_BIT == 0) {
            if (result < 0) throw IOException("VLQ value overflows a signed Long")
            return result
        }
        shift += PAYLOAD_BITS
    }
}

/**
 * Reads a non-negative [Int] written by [writeVarInt] (or by [writeVarLong] for a value that fits an
 * [Int]).
 *
 * @throws EOFException if the stream ends before the value's final byte.
 * @throws IOException if the value is malformed (see [readVarLong]) or exceeds [Int.MAX_VALUE].
 * @see writeVarInt
 */
fun InputStream.readVarInt(): Int {
    val value = readVarLong()
    if (value > Int.MAX_VALUE) throw IOException("VLQ value $value exceeds Int range")
    return value.toInt()
}
