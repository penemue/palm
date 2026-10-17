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
package com.github.penemue.palm.lz77

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
 * Greedy LZ77 with an adaptive binary range coder over the whole token stream.
 */
class GreedyRangeCompressionProvider : CompressionProvider {
    override val id: String = "lz77-greedy"
    override fun create(): CompressionMethod = Lz77CompressionMethod(false)
}

/**
 * One-byte lazy LZ77 with an adaptive binary range coder over the whole token stream.
 */
class LazyRangeCompressionProvider : CompressionProvider {
    override val id: String = "lz77-lazy"
    override fun create(): CompressionMethod = Lz77CompressionMethod(true)
}

/**
 * LZ77 body coder: the geometry header followed by the range-coded token stream, parsed greedily or
 * with a one-byte lookahead.
 */
private class Lz77CompressionMethod(private val lazy: Boolean) : CompressionMethod() {
    override val family: Int get() = FAMILY_LZ77

    override fun compressBody(source: ByteArray, output: OutputStream, config: CompressionConfig?) {
        val lz77Config = when (config) {
            null -> Lz77Config()
            is Lz77Config -> config
            else -> throw IllegalArgumentException(
                "LZ77 compression requires an Lz77Config but got ${config::class.simpleName}",
            )
        }
        writeGeometry(output, lz77Config, LZ77_CURRENT_FORMAT)
        // The single list of recent distances this operation runs on: the consumer queries it and
        // the parser advances it once per emitted match.
        val repDistances = RepDistances(lz77Config.repDistanceCount)
        val consumer = RangeLz77TokenConsumer(lz77Config, output, repDistances, LZ77_CURRENT_FORMAT)
        if (lazy) {
            source.lz77LazyEncode(consumer, repDistances)
        } else {
            source.lz77GreedyEncode(consumer, repDistances)
        }
    }

    override fun decompressBody(input: InputStream, output: OutputStream, originalSize: Long) {
        val (config, format) = readGeometry(input)
        // Mirrors the encoder's single list: the reader queries it and the decode loop advances it
        // once per decoded match.
        val repDistances = RepDistances(config.repDistanceCount)
        val reader = RangeLz77TokenReader(config, input, repDistances, format)
        decodeTokens(reader, output, config, originalSize, repDistances)
    }
}

/**
 * Writes the geometry header: the version prefix, then the fields a decoder needs to size its
 * window and token fields. The 0.1.0 parameter set is written without the prefix, in the layout a
 * 0.1.0 decoder reads.
 */
private fun writeGeometry(output: OutputStream, config: Lz77Config, format: Lz77Format) {
    if (format.version != LZ77_LEGACY_VERSION) {
        output.writeVarInt(GEOMETRY_VERSION_MARKER)
        output.writeVarInt(format.version)
    }
    output.writeVarInt(config.windowBits)
    output.writeVarInt(config.lengthBits)
    output.writeVarInt(config.minMatch)
    output.writeVarInt(config.repDistanceCount)
}

/**
 * Reads the geometry header. A leading [GEOMETRY_VERSION_MARKER] is followed by the format version;
 * any other leading value is the `windowBits` of a stream predating the version prefix.
 *
 * @param input source positioned at the geometry header.
 * @throws IOException on a header no format version, or no geometry, can be built from.
 */
private fun readGeometry(input: InputStream): Pair<Lz77Config, Lz77Format> {
    try {
        val first = input.readVarInt()
        val (format, windowBits) = if (first == GEOMETRY_VERSION_MARKER) {
            val format = try {
                lz77Format(input.readVarInt())
            } catch (exception: IllegalStateException) {
                throw IOException(exception.message, exception)
            }
            format to input.readVarInt()
        } else {
            LZ77_LEGACY_FORMAT to first
        }
        val lengthBits = input.readVarInt()
        val minMatch = input.readVarInt()
        val repDistanceCount = input.readVarInt()
        // The geometry comes from an untrusted header, and Lz77Config bounds every field of it
        // before the decoder turns windowBits into a 2^windowBits ring allocation.
        return try {
            Lz77Config(
                windowBits = windowBits,
                lengthBits = lengthBits,
                minMatch = minMatch,
                repDistanceCount = repDistanceCount,
            ) to format
        } catch (exception: IllegalArgumentException) {
            throw IOException("Invalid LZ77 geometry", exception)
        }
    } catch (exception: EOFException) {
        throw EOFException("Truncated LZ77 geometry").also { it.initCause(exception) }
    }
}

/**
 * Replays the token stream into [output] until [originalSize] bytes are produced, rejecting a
 * distance or length the geometry cannot honor.
 *
 * @param reader source of tokens, sharing [repDistances] with this loop.
 * @param output destination for the reconstructed bytes.
 * @param config geometry the decoded fields are validated against.
 * @param originalSize byte count the stream is known to reproduce.
 * @param repDistances the operation's list of recent match distances, advanced once per match.
 * @throws IOException on a token the geometry or the remaining size cannot honor.
 */
private fun decodeTokens(
    reader: Lz77TokenReader,
    output: OutputStream,
    config: Lz77Config,
    originalSize: Long,
    repDistances: RepDistances,
) {
    val decoder = Lz77Decoder(config, output, repDistances)
    var produced = 0L
    while (produced < originalSize) {
        if (!reader.readIsMatch()) {
            decoder.literal(reader.readLiteral(decoder.previousByte))
            produced++
        } else {
            val length = reader.readLength()
            val distance = reader.readDistance()
            if (distance !in 1..config.maxDistance) {
                throw IOException("Invalid match distance $distance")
            }
            if (length.toLong() > originalSize - produced) {
                throw IOException("Match length $length overshoots original size $originalSize at $produced")
            }
            repDistances.remember(distance)
            decoder.match(length, distance)
            produced += length
        }
    }
    decoder.finish()
}

/**
 * Family byte written into the common header for every LZ77 variant.
 */
private const val FAMILY_LZ77 = 1
