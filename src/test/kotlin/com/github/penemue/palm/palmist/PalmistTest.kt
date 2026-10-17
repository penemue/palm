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

import com.github.penemue.palm.CompressionProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The only palmist provider, covering the whole family.
 */
private const val PALMIST_PROVIDER_ID = "palmist"

/**
 * Base64 of a payload written by release 0.1.0, which carries no format version of its own.
 */
private const val LEGACY_PAYLOAD =
    "UEFMTQI6BQAoGD2Ihc4iKMxG2dS6vUZJmGvkna8iVvp8fKAiuPlpcmxl2eS8PP7c26D1QHyasn1RJgAA"

/**
 * Base64 of the bytes [LEGACY_PAYLOAD] must restore to.
 */
private const val LEGACY_PLAINTEXT =
    "UGFsbSAwLjEuMCBjb21wYXRpYmlsaXR5IGZpeHR1cmU6IGFicmFjYWRhYnJhIGFicmFjYWRhYnJhCg=="

/**
 * Index of the palmist geometry in a payload of fewer than 128 bytes: four magic bytes, the family
 * byte and a one-byte original size precede it.
 */
private const val GEOMETRY_OFFSET = 6

/**
 * A format version no release has registered.
 */
private const val UNKNOWN_VERSION = 99

/**
 * Common header of an empty palmist payload: the magic, the palmist family byte and an original size
 * of zero.
 */
private val EMPTY_PALMIST_HEADER = "PALM".toByteArray() + byteArrayOf(2, 0)

/**
 * Flush bytes a range coder emits when nothing was coded.
 */
private const val RANGE_FLUSH_BYTES = 5

/**
 * Consecutive held chains a run-bucket test walks through, past the last step of the run ladder.
 */
private const val LONG_RUN = 16

/**
 * A saturation ceiling one past what the default two-bit confidence field holds.
 */
private const val TOO_CONFIDENT = 4

/**
 * A model of [orderCount] orders laid out as [format] lays it out.
 */
private fun modelOf(orderCount: Int, format: PalmistFormat = PALMIST_CURRENT_FORMAT) = MultiOrderByteModel(
    orderCount,
    format.baseOrder,
    format.confidenceSlotBits,
    format.maxConfidence,
    format.shallowSlotBits,
)

/**
 * Covers the palmist provider end to end: prediction quality, geometry validation and the payloads
 * earlier releases wrote.
 */
class PalmistTest {

    /**
     * Compresses [data] and reconstructs the payload, asserting a lossless round trip.
     *
     * @param data payload the provider must return byte for byte.
     * @param config geometry to compress with, or `null` to use the method's default.
     */
    private fun roundTrip(data: ByteArray, config: PalmistConfig? = null) {
        val method = CompressionProvider.find(PALMIST_PROVIDER_ID).create()
        val packed = ByteArrayOutputStream()
        method.compress(ByteArrayInputStream(data), packed, config)
        val restored = ByteArrayOutputStream()
        // Decompression takes no config: the payload carries the geometry the decoder needs.
        method.decompress(ByteArrayInputStream(packed.toByteArray()), restored)
        assertContentEquals(data, restored.toByteArray(), "round trip mismatch for ${data.size} bytes")
    }

    /**
     * Bytes the default geometry packs [data] into.
     */
    private fun compressedSize(data: ByteArray): Int {
        val packed = ByteArrayOutputStream()
        CompressionProvider.find(PALMIST_PROVIDER_ID).create().compress(ByteArrayInputStream(data), packed)
        return packed.size()
    }

    // ---- prediction ------------------------------------------------------------------------------

    @Test
    fun `a fully predicted stretch costs a small fraction of a bit per byte`() {
        // Zero-filled tables predict 0 for every context, so all-zero input is matched by the very
        // first step of every chain.
        val size = 100_000
        val packed = compressedSize(ByteArray(size))
        assertTrue(packed * 8.0 / size < 0.1, "fully predicted input packed to $packed bytes")
    }

    @Test
    fun `an order-2 pattern is predicted once every order has learned it`() {
        val data = ByteArray(50_000) { if (it % 2 == 0) 'a'.code.toByte() else 'b'.code.toByte() }
        val packed = compressedSize(data)
        assertTrue(packed * 8.0 / data.size < 0.1, "periodic input packed to $packed bytes")
    }

    @Test
    fun `a pattern only a deep order can separate is still predicted`() {
        // In "abcabX" the order-2 context "ab" is followed by both 'c' and 'X', so orders 1 and 2
        // cannot resolve it alone; the deeper orders can, and the chain offers every order at once.
        val unit = "abcabX".toByteArray()
        val data = ByteArray(60_000) { unit[it % unit.size] }
        val packed = compressedSize(data)
        assertTrue(packed * 8.0 / data.size < 0.2, "deep-order pattern packed to $packed bytes")
    }

    // ---- round-trip coverage --------------------------------------------------------------------

    @Test
    fun `empty input`() = roundTrip(ByteArray(0))

    @Test
    fun `single byte`() = roundTrip(byteArrayOf(42))

    @Test
    fun `all identical bytes round trip`() = roundTrip(ByteArray(10_000) { 'A'.code.toByte() })

    @Test
    fun `bytes that coincide with the predicted zero byte round trip`() = roundTrip(ByteArray(5_000))

    @Test
    fun `input that begins with zeros round trips`() {
        roundTrip(ByteArray(10_000) { if (it < 6_000) 0 else (it % 251).toByte() })
    }

    @Test
    fun `repeating pattern`() {
        val unit = "abcabcabcXYZ".toByteArray()
        roundTrip(ByteArray(50_000) { unit[it % unit.size] })
    }

    @Test
    fun `text like data`() {
        val sentence = "the quick brown fox jumps over the lazy dog. ".toByteArray()
        val out = ByteArrayOutputStream()
        repeat(2_000) { out.write(sentence) }
        roundTrip(out.toByteArray())
    }

    @Test
    fun `incompressible random data still round trips`() {
        val data = ByteArray(20_000)
        Random(12345).nextBytes(data)
        roundTrip(data)
    }

    @Test
    fun `every byte value round trips`() {
        roundTrip(ByteArray(256) { it.toByte() })
    }

    @Test
    fun `various sizes round trip`() {
        val rnd = Random(99)
        for (size in intArrayOf(0, 1, 2, 3, 4, 5, 16, 17, 255, 256, 257, 1023, 4096, 65_537)) {
            val data = ByteArray(size)
            for (i in 0 until size) {
                data[i] = if (i % 3 == 0) rnd.nextInt(256).toByte() else (i % 7).toByte()
            }
            roundTrip(data)
        }
    }

    // ---- predictions -----------------------------------------------------------------------------

    @Test
    fun `predictions are distinct, confidence-ordered and never empty`() {
        val data = "the quick brown fox jumps over the lazy dog. ".toByteArray()
        for (orderCount in MIN_ORDER_COUNT..MAX_ORDER_COUNT) {
            val model = modelOf(orderCount)
            repeat(200) {
                for (byte in data) {
                    assertTrue(
                        model.count in 1..orderCount,
                        "prediction count ${model.count} out of range at orderCount $orderCount",
                    )
                    val seen = mutableSetOf<Int>()
                    val predictions = model.predictions()
                    for (index in 0 until model.count) {
                        assertTrue(predictions[index] in 0..255, "predicted byte out of range")
                        assertTrue(seen.add(predictions[index]), "duplicate predicted byte")
                        if (index > 0) {
                            assertTrue(
                                model.confidenceAt(index - 1) >= model.confidenceAt(index),
                                "predictions are not ordered by descending confidence",
                            )
                        }
                    }
                    model.learn(byte.toInt() and 0xFF)
                }
            }
        }
    }

    // ---- geometry and validation -----------------------------------------------------------------

    @Test
    fun `payload written by release 0_1_0 remains readable`() {
        val restored = ByteArrayOutputStream()
        CompressionProvider.find(PALMIST_PROVIDER_ID).create().decompress(
            ByteArrayInputStream(Base64.getDecoder().decode(LEGACY_PAYLOAD)),
            restored,
        )
        assertContentEquals(Base64.getDecoder().decode(LEGACY_PLAINTEXT), restored.toByteArray())
    }

    @Test
    fun `unknown palmist format version is rejected`() {
        val packed = ByteArrayOutputStream()
        CompressionProvider.find(PALMIST_PROVIDER_ID).create().compress(
            ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            packed,
        )
        // The current geometry opens with the version marker, then the version.
        val bytes = packed.toByteArray().also { it[GEOMETRY_OFFSET + 1] = UNKNOWN_VERSION.toByte() }
        assertFailsWith<IOException> {
            CompressionProvider.find(PALMIST_PROVIDER_ID).create().decompress(
                ByteArrayInputStream(bytes),
                ByteArrayOutputStream(),
            )
        }
    }

    @Test
    fun `a version-prefixed body decodes with the version it names`() {
        val legacy = Base64.getDecoder().decode(LEGACY_PAYLOAD)
        val prefix = byteArrayOf(GEOMETRY_VERSION_MARKER.toByte(), PALMIST_LEGACY_VERSION.toByte())
        val prefixed =
            legacy.copyOfRange(0, GEOMETRY_OFFSET) + prefix + legacy.copyOfRange(GEOMETRY_OFFSET, legacy.size)
        val restored = ByteArrayOutputStream()
        CompressionProvider.find(PALMIST_PROVIDER_ID).create().decompress(ByteArrayInputStream(prefixed), restored)
        assertContentEquals(Base64.getDecoder().decode(LEGACY_PLAINTEXT), restored.toByteArray())
    }

    @Test
    fun `a legacy payload recording more orders than the compressor writes still decodes`() {
        // The legacy geometry admitted every order count the history fits, past the current bound.
        val orderCount = PALMIST_LEGACY_FORMAT.maxDecodableOrderCount
        assertTrue(orderCount > MAX_ORDER_COUNT, "the legacy bound must reach past the write bound")
        val payload = EMPTY_PALMIST_HEADER + byteArrayOf(orderCount.toByte()) + ByteArray(RANGE_FLUSH_BYTES)
        val restored = ByteArrayOutputStream()
        CompressionProvider.find(PALMIST_PROVIDER_ID).create().decompress(ByteArrayInputStream(payload), restored)
        assertEquals(0, restored.size())
    }

    @Test
    fun `every supported order count round trips`() {
        val sentence = "the quick brown fox jumps over the lazy dog. ".toByteArray()
        val data = ByteArrayOutputStream().apply { repeat(500) { write(sentence) } }.toByteArray()
        for (orderCount in MIN_ORDER_COUNT..PALMIST_CURRENT_FORMAT.maxOrderCount) {
            roundTrip(data, PalmistConfig(orderCount = orderCount))
        }
    }

    @Test
    fun `a custom order count travels in the payload`() {
        val data = ByteArray(40_000) { (it % 251).toByte() }
        val method = CompressionProvider.find(PALMIST_PROVIDER_ID).create()

        fun compress(config: PalmistConfig?): ByteArray = ByteArrayOutputStream().also {
            method.compress(ByteArrayInputStream(data), it, config)
        }.toByteArray()

        val defaulted = compress(null)
        val deepest = compress(PalmistConfig(orderCount = PALMIST_CURRENT_FORMAT.maxOrderCount))
        assertTrue(!defaulted.contentEquals(deepest), "the two geometries must produce different payloads")
        // Both decode without a config, since each payload carries the geometry it was written with.
        for (packed in listOf(defaulted, deepest)) {
            val restored = ByteArrayOutputStream()
            method.decompress(ByteArrayInputStream(packed), restored)
            assertContentEquals(data, restored.toByteArray(), "round trip mismatch")
        }
    }

    @Test
    fun `an order count outside the supported range is rejected`() {
        assertFailsWith<IllegalArgumentException> { PalmistConfig(orderCount = MIN_ORDER_COUNT - 1) }
        assertFailsWith<IllegalArgumentException> { PalmistConfig(orderCount = MAX_ORDER_COUNT + 1) }
        PalmistConfig(orderCount = MIN_ORDER_COUNT) // the bounds themselves stay usable
        PalmistConfig(orderCount = MAX_ORDER_COUNT)
    }

    @Test
    fun `a smaller run bucket count folds every run inside it`() {
        // A fresh model offers one prediction at confidence zero, and a chain that holds leaves the
        // exhausted history at zero, so the chain context is the run bucket alone.
        for (runBuckets in 1..RUN_BUCKETS) {
            val model = modelOf(DEFAULT_ORDER_COUNT)
            val state = ChainState(DEFAULT_ORDER_COUNT, DEFAULT_CONFIDENCE_STATES, EXHAUSTED_HISTORY_STATES, runBuckets)
            val contexts = buildSet {
                repeat(LONG_RUN) {
                    add(state.chainContext(model))
                    state.advance(0, exhausted = false)
                }
            }
            assertEquals((0 until runBuckets).toSet(), contexts, "runBuckets $runBuckets")
        }
    }

    @Test
    fun `a format with fewer run buckets round trips`() {
        val sentence = "the quick brown fox jumps over the lazy dog. ".toByteArray()
        val data = ByteArrayOutputStream().apply { repeat(200) { write(sentence) } }.toByteArray()
        val config = PalmistConfig()
        for (runBuckets in 1 until RUN_BUCKETS) {
            val format = PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER, runBuckets = runBuckets)
            val packed = ByteArrayOutputStream()
            data.palmistEncode(RangePalmistTokenConsumer(config, packed, format))
            val reader = RangePalmistTokenReader(config.orderCount, ByteArrayInputStream(packed.toByteArray()), format)
            val restored = ByteArray(data.size) { reader.readLiteral().toByte() }
            assertContentEquals(data, restored, "runBuckets $runBuckets")
        }
    }

    @Test
    fun `the derived bounds of a format follow its own parameters`() {
        for (baseOrder in 1..BASE_ORDER) {
            val format = PalmistFormat(PALMIST_LEGACY_VERSION, baseOrder)
            assertEquals(MAX_CONTEXT_BYTES - baseOrder + 1, format.maxDecodableOrderCount, "baseOrder $baseOrder")
            assertEquals(minOf(MAX_ORDER_COUNT, format.maxDecodableOrderCount), format.maxOrderCount)
        }
        val fewerStates =
            PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER, confidenceStates = DEFAULT_CONFIDENCE_STATES - 1)
        assertEquals(DEFAULT_CONFIDENCE_STATES - 2, fewerStates.maxConfidence)
    }

    @Test
    fun `a format parameter outside its bounds is rejected`() {
        val invalid = listOf(
            { PalmistFormat(PALMIST_CURRENT_VERSION, 0) },
            { PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER + 1) },
            { PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER, confidenceStates = 0) },
            { PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER, liveCountBuckets = 0) },
            { PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER, exhaustedHistoryStates = 0) },
            {
                PalmistFormat(
                    PALMIST_CURRENT_VERSION,
                    BASE_ORDER,
                    exhaustedHistoryStates = EXHAUSTED_HISTORY_STATES - 1,
                )
            },
            { PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER, runBuckets = 0) },
        )
        for ((index, build) in invalid.withIndex()) {
            assertFailsWith<IllegalArgumentException>("case $index") { build() }
        }
    }

    @Test
    fun `the chain model runs at the adaptation rate of the format`() {
        val sentence = "the quick brown fox jumps over the lazy dog. ".toByteArray()
        val data = ByteArrayOutputStream().apply { repeat(200) { write(sentence) } }.toByteArray()
        val config = PalmistConfig()
        fun encode(format: PalmistFormat) = ByteArrayOutputStream().also {
            data.palmistEncode(RangePalmistTokenConsumer(config, it, format))
        }.toByteArray()

        val slower = PalmistFormat(
            PALMIST_CURRENT_VERSION,
            BASE_ORDER,
            chainModelAdaptShift = PALMIST_CURRENT_FORMAT.chainModelAdaptShift + 1,
        )
        val packed = encode(slower)
        assertTrue(!packed.contentEquals(encode(PALMIST_CURRENT_FORMAT)), "the chain rate must shape the stream")
        val reader = RangePalmistTokenReader(config.orderCount, ByteArrayInputStream(packed), slower)
        assertContentEquals(data, ByteArray(data.size) { reader.readLiteral().toByte() })
    }

    @Test
    fun `the model rejects too few orders to chain`() {
        for (baseOrder in 1..BASE_ORDER) {
            val format = PalmistFormat(PALMIST_LEGACY_VERSION, baseOrder)
            modelOf(MIN_ORDER_COUNT, format) // the bound itself stays usable
            assertFailsWith<IllegalArgumentException>("baseOrder=$baseOrder") {
                modelOf(MIN_ORDER_COUNT - 1, format)
            }
        }
    }

    @Test
    fun `the model holds an ensemble deeper than the compressor writes`() {
        // A stream written before the shipped bound narrowed records more orders than it now admits,
        // and the model has to hold every one of them the history still fits.
        for (baseOrder in 1..BASE_ORDER) {
            val format = PalmistFormat(PALMIST_LEGACY_VERSION, baseOrder)
            for (orderCount in MAX_ORDER_COUNT..format.maxDecodableOrderCount) {
                modelOf(orderCount, format)
            }
        }
    }

    @Test
    fun `a table geometry the confidence packing cannot hold is rejected with its values`() {
        val narrowTable = assertFailsWith<IllegalArgumentException> {
            modelOf(DEFAULT_ORDER_COUNT, PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER, shallowSlotBits = 1))
        }
        assertTrue(narrowTable.message.orEmpty().contains("slotBits"), "${narrowTable.message}")
        assertTrue(narrowTable.message.orEmpty().contains("was 1"), "${narrowTable.message}")

        val tooConfident = assertFailsWith<IllegalArgumentException> {
            val format = PalmistFormat(PALMIST_CURRENT_VERSION, BASE_ORDER, confidenceStates = TOO_CONFIDENT + 1)
            modelOf(DEFAULT_ORDER_COUNT, format)
        }
        assertTrue(tooConfident.message.orEmpty().contains("maxConfidence"), "${tooConfident.message}")
        assertTrue(tooConfident.message.orEmpty().contains("was $TOO_CONFIDENT"), "${tooConfident.message}")
    }

    @Test
    fun `the model rejects an ensemble whose deepest order outgrows the history`() {
        for (baseOrder in 1..BASE_ORDER) {
            val format = PalmistFormat(PALMIST_LEGACY_VERSION, baseOrder)
            assertFailsWith<IllegalArgumentException>("baseOrder=$baseOrder") {
                modelOf(format.maxDecodableOrderCount + 1, format)
            }
        }
    }

    @Test
    fun `compress accepts a palmist config and rejects a config from another family`() {
        val method = CompressionProvider.find(PALMIST_PROVIDER_ID).create()
        val source = "repetitive repetitive repetitive".toByteArray()
        method.compress(ByteArrayInputStream(source), ByteArrayOutputStream(), PalmistConfig())
        val alien = object : com.github.penemue.palm.CompressionConfig {}
        assertFailsWith<IllegalArgumentException> {
            method.compress(ByteArrayInputStream(source), ByteArrayOutputStream(), alien)
        }
    }

    @Test
    fun `truncated payload is rejected`() {
        val method = CompressionProvider.find(PALMIST_PROVIDER_ID).create()
        val packed = ByteArrayOutputStream().also {
            method.compress(ByteArrayInputStream("repetitive repetitive repetitive".toByteArray()), it)
        }.toByteArray()
        assertFailsWith<IOException> {
            method.decompress(ByteArrayInputStream(packed.copyOf(packed.size - 1)), ByteArrayOutputStream())
        }
    }

    @Test
    fun `a payload of another family is rejected`() {
        val packed = ByteArrayOutputStream().also {
            CompressionProvider.find("lz77-greedy").create()
                .compress(ByteArrayInputStream("repetitive repetitive".toByteArray()), it)
        }.toByteArray()
        assertFailsWith<IOException> {
            CompressionProvider.find(PALMIST_PROVIDER_ID).create()
                .decompress(ByteArrayInputStream(packed), ByteArrayOutputStream())
        }
    }
}
