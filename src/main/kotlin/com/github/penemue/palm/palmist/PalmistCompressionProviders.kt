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

import com.github.penemue.palm.CompressionConfig
import com.github.penemue.palm.CompressionMethod
import com.github.penemue.palm.CompressionProvider
import com.github.penemue.palm.vlq.readVarInt
import com.github.penemue.palm.vlq.writeVarInt
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Next-byte prediction by several orders at once, range-coded: every literal is offered to the bytes
 * the orders predict, and only a byte none of them proposed is coded in full.
 */
class RangePalmistCompressionProvider : CompressionProvider {
    override val id: String = "palmist"
    override fun create(): CompressionMethod = PalmistCompressionMethod()
}

private class PalmistCompressionMethod : CompressionMethod() {
    override val family: Int get() = FAMILY_PALMIST

    override fun compressBody(source: ByteArray, output: OutputStream, config: CompressionConfig?) {
        val palmistConfig = when (config) {
            null -> PalmistConfig()
            is PalmistConfig -> config
            else -> throw IllegalArgumentException(
                "Prediction compression requires a PalmistConfig but got ${config::class.simpleName}",
            )
        }
        PALMIST_CURRENT_FORMAT.validate(palmistConfig)
        writeGeometry(output, palmistConfig, PALMIST_CURRENT_FORMAT)
        source.palmistEncode(RangePalmistTokenConsumer(palmistConfig, output, PALMIST_CURRENT_FORMAT))
    }

    override fun decompressBody(input: InputStream, output: OutputStream, originalSize: Long) {
        val (orderCount, format) = readGeometry(input)
        decodeTokens(RangePalmistTokenReader(orderCount, input, format), output, originalSize)
    }
}

/**
 * Writes the geometry: the version marker, the format version behind it, then the order count.
 */
private fun writeGeometry(output: OutputStream, config: PalmistConfig, format: PalmistFormat) {
    output.writeVarInt(GEOMETRY_VERSION_MARKER)
    output.writeVarInt(format.version)
    output.writeVarInt(config.orderCount)
}

/**
 * Reads the order count and the parameter set it is to be decoded with. A geometry opening with the
 * version marker records its version right after it; anything else is the order count of a stream
 * written before versions were recorded.
 */
private fun readGeometry(input: InputStream): Pair<Int, PalmistFormat> {
    try {
        val first = input.readVarInt()
        val (format, orderCount) = if (first == GEOMETRY_VERSION_MARKER) {
            val format = try {
                palmistFormat(input.readVarInt())
            } catch (exception: IllegalStateException) {
                throw IOException(exception.message, exception)
            }
            format to input.readVarInt()
        } else {
            PALMIST_LEGACY_FORMAT to first
        }
        // The geometry comes from an untrusted header, so bound the order count before the models
        // are sized from it. The bound is the deepest ensemble the model can hold rather than the
        // deepest one the compressor writes, so a stream written under a wider bound still decodes.
        return try {
            require(orderCount in MIN_ORDER_COUNT..format.maxDecodableOrderCount) {
                "orderCount must be in $MIN_ORDER_COUNT..${format.maxDecodableOrderCount}" +
                    " for format version ${format.version}"
            }
            orderCount to format
        } catch (exception: IllegalArgumentException) {
            throw IOException("Invalid prediction geometry", exception)
        }
    } catch (exception: EOFException) {
        throw EOFException("Truncated prediction geometry").also { it.initCause(exception) }
    }
}

/**
 * Replays a prediction token stream into [output].
 *
 * Decoding ends when [originalSize] bytes have been produced, so no end marker is needed.
 */
private fun decodeTokens(reader: PalmistTokenReader, output: OutputStream, originalSize: Long) {
    val decoder = PalmistDecoder(output)
    var produced = 0L
    while (produced < originalSize) {
        decoder.literal(reader.readLiteral())
        produced++
    }
    decoder.finish()
}

/**
 * Family byte written into the common header for the prediction compressor.
 */
private const val FAMILY_PALMIST = 2
