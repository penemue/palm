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
package com.github.penemue.palm.benchmarks

import com.github.penemue.palm.CompressionProvider
import java.io.ByteArrayInputStream
import java.io.OutputStream

internal fun encode(source: ByteArray, provider: CompressionProvider): EncodedSize {
    val output = CountingOutputStream()
    provider.create().compress(ByteArrayInputStream(source), output)
    return EncodedSize(output.bytesWritten)
}

/**
 * Size of one encoded corpus member, framing header included.
 */
internal data class EncodedSize(val bytes: Long)

/**
 * Discards every byte written, keeping only their count.
 */
private class CountingOutputStream : OutputStream() {
    var bytesWritten = 0L
        private set

    override fun write(b: Int) {
        bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        bytesWritten += len
    }
}
