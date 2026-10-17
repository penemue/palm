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

import com.github.penemue.palm.range.DEFAULT_RANGE_FORMAT
import com.github.penemue.palm.range.RangeFormat

/**
 * Parameters that define the LZ77 stream model for one format version.
 *
 * A decoder must run on the very parameters its encoder used, so the values travel as the version
 * alone and every field below is fixed for that version.
 *
 * @property version value the geometry header carries to select this parameter set.
 * @property range probability scale, renormalization threshold and warm-up rate the coded stream runs
 * on.
 * @property distanceAlignBits lowest mantissa bits of a spelled-out distance coded adaptively, one
 * probability per absolute bit position, the bits above them written raw.
 * @property shortLengthBits width of the short-range length tree; the short range holds the lowest
 * `2^shortLengthBits` length values, narrowed when the geometry offers fewer.
 * @property selectorRunBuckets run lengths the selector tells apart before saturating; it holds
 * twice as many contexts, the literal buckets first and the match buckets after them.
 * @property repRunBuckets buckets the rep bit folds the preceding match run into, bucket `0` being
 * a match opening a run after literals.
 * @property repIndexRunBuckets buckets the rep index chain folds that same run into, on top of its
 * per-position probabilities.
 * @property selectorModelAdaptShift rate the selector contexts settle at, tracking how likely a
 * match becomes once a run of one token type is under way.
 * @property literalModelAdaptShift rate the literal tree settles at, tracking which byte values
 * follow each context.
 * @property lengthModelAdaptShift rate the range flag and both length trees settle at, tracking how
 * long matches run.
 * @property repModelAdaptShift rate the rep bit and the rep index chain settle at, tracking how
 * often a match reuses a remembered distance and which one it names.
 * @property distanceSlotModelAdaptShift rate the slot tree settles at, tracking the magnitude
 * classes spelled-out distances fall into.
 * @property distanceAlignModelAdaptShift rate the align models settle at, tracking a recurring
 * alignment in the low bits of spelled-out distances.
 * @property lengthPreviousGroups groups the previous match's length folds into as the context of
 * the next one; the range flag and the short tree hold twice as many contexts.
 */
internal class Lz77Format(
    val version: Int,
    val range: RangeFormat = DEFAULT_RANGE_FORMAT,
    val distanceAlignBits: Int = DISTANCE_ALIGN_BITS,
    val shortLengthBits: Int = SHORT_LENGTH_BITS,
    val selectorRunBuckets: Int = SELECTOR_RUN_BUCKETS,
    val repRunBuckets: Int = REP_RUN_BUCKETS,
    val repIndexRunBuckets: Int = REP_INDEX_RUN_BUCKETS,
    val selectorModelAdaptShift: Int = SELECTOR_MODEL_ADAPT_SHIFT,
    val literalModelAdaptShift: Int = LITERAL_MODEL_ADAPT_SHIFT,
    val lengthModelAdaptShift: Int = LENGTH_MODEL_ADAPT_SHIFT,
    val repModelAdaptShift: Int = REP_MODEL_ADAPT_SHIFT,
    val distanceSlotModelAdaptShift: Int = DISTANCE_SLOT_MODEL_ADAPT_SHIFT,
    val distanceAlignModelAdaptShift: Int = DISTANCE_ALIGN_MODEL_ADAPT_SHIFT,
    val lengthPreviousGroups: Int = LENGTH_PREV_GROUPS,
)

/**
 * Version of the 0.1.0 parameter set. Its streams carry no version prefix and are recognized by the
 * absence of [GEOMETRY_VERSION_MARKER] rather than by this value.
 */
internal const val LZ77_LEGACY_VERSION = 0

/**
 * Leading geometry value that marks a version prefix. It shares its numeric value with
 * [LZ77_LEGACY_VERSION] and stays unambiguous because a legacy geometry opens with `windowBits`,
 * which is never zero.
 */
internal const val GEOMETRY_VERSION_MARKER = 0

/**
 * Parameter set of release 0.1.0, which decodes every stream without a version prefix.
 */
internal val LZ77_LEGACY_FORMAT = Lz77Format(LZ77_LEGACY_VERSION)

/**
 * Parameter set every stream is written with. It is still the 0.1.0 set, so streams are written
 * without a version prefix and a 0.1.0 decoder reads them.
 */
internal val LZ77_CURRENT_FORMAT = LZ77_LEGACY_FORMAT

/**
 * @param version the value read after [GEOMETRY_VERSION_MARKER].
 * @throws IllegalStateException when no parameter set is registered for [version].
 */
internal fun lz77Format(version: Int): Lz77Format = when (version) {
    LZ77_LEGACY_VERSION -> LZ77_LEGACY_FORMAT
    else -> error("Unknown LZ77 format version: $version")
}

/**
 * Longest match this format can code under [config]. A match is coded as `length - minMatch`, which a
 * flag places either in the short range, the lowest `2^min(shortLengthBits, lengthBits)` values, or in
 * the long range, the `2^lengthBits` values that follow; the longest match is the one the last value
 * of the long range stands for.
 */
internal fun Lz77Format.maxMatch(config: Lz77Config): Int =
    config.minMatch + shortLengthCount(config.lengthBits, shortLengthBits) + (1 shl config.lengthBits) - 1
