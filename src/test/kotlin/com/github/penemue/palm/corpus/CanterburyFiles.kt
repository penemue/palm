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
package com.github.penemue.palm.corpus

/**
 * Classpath directory the corpus files sit in.
 */
private const val CANTERBURY_PATH = "/corpus/canterbury"

/**
 * Summed size of every file of [CANTERBURY_FILES]. A consumer totals the bytes it actually read and
 * compares them against this, which catches a truncated or misplaced corpus.
 */
const val CANTERBURY_CORPUS_SIZE = 2_810_784L

/**
 * The complete [Canterbury Corpus](https://corpus.canterbury.ac.nz/), in the order the published set
 * lists it.
 */
val CANTERBURY_FILES = listOf(
    "alice29.txt",
    "asyoulik.txt",
    "cp.html",
    "fields.c",
    "grammar.lsp",
    "kennedy.xls",
    "lcet10.txt",
    "plrabn12.txt",
    "ptt5",
    "sum",
    "xargs.1",
)

/**
 * Anchor for resolving the corpus resources on the classpath.
 */
private object CanterburyCorpus

/**
 * Reads one file of [CANTERBURY_FILES] whole.
 *
 * @param file name of the file, which must be one of [CANTERBURY_FILES].
 */
fun canterburyBytes(file: String): ByteArray =
    checkNotNull(CanterburyCorpus.javaClass.getResourceAsStream("$CANTERBURY_PATH/$file")) {
        "Missing corpus resource: $CANTERBURY_PATH/$file"
    }.use { it.readBytes() }
