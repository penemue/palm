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

import com.github.penemue.palm.vlq.readVarLong
import com.github.penemue.palm.vlq.writeVarLong
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A stateful compression method created for one or more sequential operations.
 *
 * Every payload starts with a **common header** written and validated here, shared by all methods:
 * the four-byte `PALM` signature, a single [family] byte, and the original (uncompressed) size as an
 * unsigned [variable-length quantity][com.github.penemue.palm.vlq.writeVarLong]. Everything after
 * that is the method-specific **body**, produced and consumed by [compressBody] / [decompressBody].
 *
 * The [family] byte identifies the algorithm and body layout; [decompress] rejects a payload of an
 * unrelated family.
 *
 * Implementations must not close caller-owned streams.
 */
abstract class CompressionMethod {

    /**
     * Byte (in `0..255`) identifying the family this method belongs to. All methods that read each
     * other's payloads must share the same value.
     */
    protected abstract val family: Int

    /**
     * Compresses all bytes from [input] into [output], writing the common header followed by the
     * method-specific body.
     *
     * @param config family-specific tuning for this operation; when `null` the family's built-in
     * defaults are used. A configuration that belongs to a different family is rejected.
     */
    fun compress(input: InputStream, output: OutputStream, config: CompressionConfig? = null) {
        val source = input.readBytes()
        writeCommonHeader(output, source.size.toLong())
        compressBody(source, output, config)
    }

    /**
     * Decompresses all bytes from [input] into [output] after validating the common header.
     */
    fun decompress(input: InputStream, output: OutputStream) {
        val originalSize = readCommonHeader(input)
        decompressBody(input, output, originalSize)
    }

    /**
     * Writes the method-specific body (any sub-header plus the packed data) for [source] into
     * [output]. The common header has already been written.
     *
     * @param config the family-specific configuration passed to [compress], or `null` for defaults.
     * An implementation must reject a configuration that is not of its own family.
     */
    protected abstract fun compressBody(source: ByteArray, output: OutputStream, config: CompressionConfig?)

    /**
     * Reads the method-specific body from [input] and writes the reconstructed bytes into [output].
     * The common header has already been consumed; [originalSize] is the validated original size.
     */
    protected abstract fun decompressBody(input: InputStream, output: OutputStream, originalSize: Long)

    private fun writeCommonHeader(output: OutputStream, originalSize: Long) {
        require(family in 0..0xFF) { "family must fit a byte but was $family" }
        DataOutputStream(output).apply {
            writeInt(MAGIC)
            writeByte(family)
        }
        output.writeVarLong(originalSize)
    }

    private fun readCommonHeader(input: InputStream): Long {
        try {
            val data = DataInputStream(input)
            val magic = data.readInt()
            if (magic != MAGIC) throw IOException("Invalid compression stream magic")
            val actualFamily = data.readUnsignedByte()
            if (actualFamily != family) {
                throw IOException("Unexpected compression stream family: $actualFamily (expected $family)")
            }
            val originalSize = input.readVarLong()
            if (originalSize !in 0..Int.MAX_VALUE.toLong()) {
                throw IOException("Unsupported original size: $originalSize")
            }
            return originalSize
        } catch (exception: EOFException) {
            throw EOFException("Truncated compression stream header").also { it.initCause(exception) }
        }
    }

    private companion object {
        const val MAGIC = 0x50414C4D
    }
}
