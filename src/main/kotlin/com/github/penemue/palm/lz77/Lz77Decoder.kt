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

import com.github.penemue.palm.util.unsignedInt
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A [Lz77TokenConsumer] that reconstructs the original bytes from a raw
 * [LZ77](https://en.wikipedia.org/wiki/LZ77_and_LZ78) token stream and writes them to an
 * [OutputStream].
 *
 * History is kept in a *cyclic byte buffer* of exactly `windowSize` bytes, so decoding runs in
 * bounded memory. A match token copies bytes from earlier in that ring: a non-overlapping match
 * whose source and destination runs each stay contiguous in the ring is copied in bulk, while
 * **overlapping** matches (where `distance` is smaller than `length`, e.g. run-length expansion
 * with `distance == 1`) and runs that wrap the ring are expanded byte by byte.
 *
 * This class is **not** thread-safe.
 *
 * @param config configuration that defines the token ranges and decoder history size.
 * @param output destination for the reconstructed bytes; flushed on [finish] but not closed.
 * @param repDistances the operation's list of recent match distances. This consumer reproduces the
 * bytes of a match rather than coding its distance field, so it never reads the list.
 */
class Lz77Decoder(
    config: Lz77Config,
    output: OutputStream,
    repDistances: RepDistances,
) : Lz77TokenConsumer(config, repDistances) {

    private val sink = output as? ByteArrayOutputStream ?: output.buffered()
    private val mask = config.windowSize - 1
    private val ring = ByteArray(config.windowSize)
    // Absolute count of bytes emitted so far; its low bits index the ring slot to write next.
    private var pos = 0

    /**
     * Byte most recently emitted, in `0..255`, or `0` before the first one.
     */
    val previousByte: Int get() = if (pos == 0) 0 else ring[(pos - 1) and mask].unsignedInt

    override fun literal(value: Int) {
        sink.write(value)
        ring[pos and mask] = value.toByte()
        pos++
    }

    /**
     * Reproduces the bytes of a match from the history ring; [lastByte] goes unused.
     */
    override fun match(length: Int, distance: Int, lastByte: Int) = match(length, distance)

    /**
     * Reproduces [length] bytes copied from [distance] bytes earlier in the output.
     *
     * @param length number of bytes to reproduce.
     * @param distance how far back the match starts; a value below [length] denotes a legal
     * overlapping copy.
     */
    fun match(length: Int, distance: Int) {
        val src = pos - distance
        val srcSlot = src and mask
        val dstSlot = pos and mask
        // Fast path: a non-overlapping match whose source and destination runs both stay within the
        // ring without wrapping can be emitted and mirrored in bulk instead of one byte at a time.
        if (distance >= length && srcSlot + length <= ring.size && dstSlot + length <= ring.size) {
            sink.write(ring, srcSlot, length)
            ring.copyInto(ring, dstSlot, srcSlot, srcSlot + length)
            pos += length
            return
        }
        // Slow path: overlapping matches or runs that wrap the ring are expanded byte by byte.
        for (i in 0 until length) {
            val b = ring[(src + i) and mask]
            sink.write(b.unsignedInt)
            ring[(pos + i) and mask] = b
        }
        pos += length
    }

    override fun finish() {
        sink.flush()
    }
}
