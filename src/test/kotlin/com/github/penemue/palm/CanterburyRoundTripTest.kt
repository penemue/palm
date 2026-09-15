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

import com.github.penemue.palm.corpus.CANTERBURY_CORPUS_SIZE
import com.github.penemue.palm.corpus.CANTERBURY_FILES
import com.github.penemue.palm.corpus.canterburyBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Lossless round-trip verification of every [CompressionProvider] over the complete Canterbury
 * Corpus. Encoded size is not measured.
 *
 * The corpus is loaded from test resources, the standard set published at
 * [The Canterbury Corpus](https://corpus.canterbury.ac.nz/).
 */
class CanterburyRoundTripTest {

    @Test
    fun `every provider round trips every Canterbury file`() {
        val providers = CompressionProvider.load()
        var totalBytes = 0L
        for (file in CANTERBURY_FILES) {
            val source = canterburyBytes(file)
            totalBytes += source.size

            for (provider in providers) {
                val packed = ByteArrayOutputStream()
                provider.create().compress(ByteArrayInputStream(source), packed)
                val restored = ByteArrayOutputStream()
                provider.create().decompress(ByteArrayInputStream(packed.toByteArray()), restored)
                assertContentEquals(source, restored.toByteArray(), "${provider.id} on $file")
            }
        }
        // Guards against a truncated or misplaced corpus silently weakening the coverage above.
        assertEquals(CANTERBURY_CORPUS_SIZE, totalBytes)
    }
}
