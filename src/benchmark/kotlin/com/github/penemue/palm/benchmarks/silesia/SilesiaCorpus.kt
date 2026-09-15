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
package com.github.penemue.palm.benchmarks.silesia

import com.github.penemue.palm.CompressionProvider
import com.github.penemue.palm.benchmarks.encode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared compression-ratio harness for the complete Silesia Compression Corpus.
 *
 * Only encoded size is measured. The official corpus archive is downloaded into
 * `build/corpora/silesia` on the first run, verified by SHA-256 and reused by later runs; corpus
 * data is never added to the repository. Each archive member is loaded and compressed independently,
 * so the whole uncompressed corpus is never held in memory at once. Reported sizes include the
 * framing header and the range coder's final flush. One row is printed per member, followed by a
 * corpus total.
 *
 * The corpus is the standard benchmark set published at
 * [The Silesia Compression Corpus](https://sun.aei.polsl.pl/~sdeor/index.php?page=silesia).
 *
 * @param providerId stable SPI identifier of the compression provider used for every member.
 */
internal fun runSilesiaBenchmark(providerId: String) {
    val provider: CompressionProvider = CompressionProvider.find(providerId)
    var totalSourceBytes = 0L
    var totalEncodedBytes = 0L

    println("method: ${provider.id}")
    println("corpus: Silesia Compression Corpus")
    println("file\toriginal bytes\tencoded bytes\tpacked bits per byte\tpacked ratio")

    forEachMember { file, source ->
        val encoded = encode(source, provider)
        val packedBitsPerByte = encoded.bytes * 8.0 / source.size
        val packedRatio = encoded.bytes.toDouble() / source.size
        println("$file\t${source.size}\t${encoded.bytes}\t${format(packedBitsPerByte)}\t${format(packedRatio)}")

        totalSourceBytes += source.size
        totalEncodedBytes += encoded.bytes
    }

    println(
        "TOTAL\t$totalSourceBytes\t$totalEncodedBytes\t" +
            "${format(totalEncodedBytes * 8.0 / totalSourceBytes)}\t" +
            format(totalEncodedBytes.toDouble() / totalSourceBytes),
    )

    assertEquals(SILESIA_CORPUS_SIZE, totalSourceBytes)
    assertTrue(totalEncodedBytes > 0)
}

/**
 * Opens the verified local archive and invokes [action] with each member's name and decompressed
 * bytes, in the fixed [SILESIA_FILES] order, holding one member at a time.
 */
private fun forEachMember(action: (file: String, source: ByteArray) -> Unit) {
    ZipFile(prepareArchive().toFile()).use { zip ->
        val actualFiles = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
        assertEquals(SILESIA_FILES.keys, actualFiles, "Unexpected Silesia archive contents")

        for ((file, expectedSize) in SILESIA_FILES) {
            val entry = checkNotNull(zip.getEntry(file)) { "Missing Silesia member: $file" }
            assertEquals(expectedSize, entry.size, "Unexpected declared size for $file")
            val source = zip.getInputStream(entry).use { it.readBytes() }
            assertEquals(expectedSize, source.size.toLong(), "Unexpected extracted size for $file")
            action(file, source)
        }
    }
}

/**
 * Returns a verified local archive, downloading it atomically when the cache is absent or invalid.
 */
private fun prepareArchive(): Path {
    val directory = Paths.get(System.getProperty("user.dir"), "build", "corpora", "silesia")
    val archive = directory.resolve("silesia.zip")
    Files.createDirectories(directory)
    if (Files.isRegularFile(archive) && sha256(archive) == ARCHIVE_SHA256) return archive

    Files.deleteIfExists(archive)
    val temporary = directory.resolve("silesia.zip.part")
    Files.deleteIfExists(temporary)
    try {
        download(URI(ARCHIVE_URL).toURL(), temporary)
        check(sha256(temporary) == ARCHIVE_SHA256) {
            "Silesia archive checksum mismatch; expected $ARCHIVE_SHA256"
        }
        try {
            Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
    return archive
}

/**
 * Downloads [source] into [target] with finite connection and read timeouts.
 */
private fun download(source: URL, target: Path) {
    println("downloading Silesia Corpus from $source")
    val connection = source.openConnection() as HttpURLConnection
    connection.connectTimeout = 30_000
    connection.readTimeout = 120_000
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "palm-compression-benchmark")
    try {
        check(connection.responseCode in 200..299) {
            "Cannot download Silesia Corpus: HTTP ${connection.responseCode} ${connection.responseMessage}"
        }
        BufferedInputStream(connection.inputStream, COPY_BUFFER_SIZE).use { input ->
            BufferedOutputStream(Files.newOutputStream(target), COPY_BUFFER_SIZE).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
            }
        }
    } finally {
        connection.disconnect()
    }
}

/**
 * The SHA-256 digest is hexadecimal in lower case.
 */
private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    BufferedInputStream(Files.newInputStream(file), COPY_BUFFER_SIZE).use { input ->
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
}

/**
 * Locale-independent, so build logs stay comparable.
 */
private fun format(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

private const val ARCHIVE_URL = "https://sun.aei.polsl.pl/~sdeor/corpus/silesia.zip"
private const val ARCHIVE_SHA256 = "0626e25f45c0ffb5dc801f13b7c82a3b75743ba07e3a71835a41e3d9f63c77af"
private const val SILESIA_CORPUS_SIZE = 211_938_580L
private const val COPY_BUFFER_SIZE = 1 shl 20

private val SILESIA_FILES = linkedMapOf(
    "dickens" to 10_192_446L,
    "mozilla" to 51_220_480L,
    "mr" to 9_970_564L,
    "nci" to 33_553_445L,
    "ooffice" to 6_152_192L,
    "osdb" to 10_085_684L,
    "reymont" to 6_627_202L,
    "samba" to 21_606_400L,
    "sao" to 7_251_944L,
    "webster" to 41_458_703L,
    "xml" to 5_345_280L,
    "x-ray" to 8_474_240L,
)
