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

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A [PalmistTokenConsumer] that reconstructs the original bytes from a raw next-byte-prediction token
 * stream and writes them to an [OutputStream].
 *
 * This class is **not** thread-safe.
 *
 * @param output destination for the reconstructed bytes; flushed on [finish] but not closed.
 */
internal class PalmistDecoder(output: OutputStream) : PalmistTokenConsumer {

    private val sink = output as? ByteArrayOutputStream ?: output.buffered()

    override fun literal(value: Int) {
        sink.write(value)
    }

    override fun finish() {
        sink.flush()
    }
}
