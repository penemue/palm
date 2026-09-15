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
        writeGeometry(output, lz77Config)
        // The single list of recent distances this operation runs on: the consumer queries it and
        // the parser advances it once per emitted match.
        val repDistances = RepDistances(lz77Config.repDistanceCount)
        val consumer = RangeLz77TokenConsumer(lz77Config, output, repDistances)
        if (lazy) {
            source.lz77LazyEncode(consumer, repDistances)
        } else {
            source.lz77GreedyEncode(consumer, repDistances)
        }
    }

    override fun decompressBody(input: InputStream, output: OutputStream, originalSize: Long) {
        val config = readGeometry(input)
        // Mirrors the encoder's single list: the reader queries it and the decode loop advances it
        // once per decoded match.
        val repDistances = RepDistances(config.repDistanceCount)
        val reader = RangeLz77TokenReader(config, input, repDistances)
        decodeTokens(reader, output, config, originalSize, repDistances)
    }
}

private fun writeGeometry(output: OutputStream, config: Lz77Config) {
    output.writeVarInt(config.windowBits)
    output.writeVarInt(config.lengthBits)
    output.writeVarInt(config.minMatch)
    output.writeVarInt(config.repDistanceCount)
}

private fun readGeometry(input: InputStream): Lz77Config {
    try {
        val windowBits = input.readVarInt()
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
            )
        } catch (exception: IllegalArgumentException) {
            throw IOException("Invalid LZ77 geometry", exception)
        }
    } catch (exception: EOFException) {
        throw EOFException("Truncated LZ77 geometry").also { it.initCause(exception) }
    }
}

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
