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

/**
 * Largest [Lz77Config.windowBits]: a ring of `2^24` bytes (16 MiB).
 */
const val MAX_WINDOW_BITS = 24

/**
 * Largest [Lz77Config.lengthBits], bounding a single match at roughly 64 KiB; see
 * [Lz77Config.maxMatch] for the exact reach.
 */
const val MAX_LENGTH_BITS = 16

/**
 * Largest [Lz77Config.minMatch], which together with [MAX_LENGTH_BITS] bounds [Lz77Config.maxMatch].
 */
const val MAX_MIN_MATCH = 1 shl MAX_LENGTH_BITS

/**
 * Largest [Lz77Config.maxChain], bounding the per-position candidate search.
 */
const val MAX_CHAIN = 4096

/**
 * Largest [Lz77Config.repDistanceCount]: the list of remembered distances a repeat is named
 * against, on both sides.
 */
const val MAX_REP_DISTANCE_COUNT = 256

/**
 * Default [Lz77Config.windowBits]: a 1 MiB history ring.
 */
const val DEFAULT_WINDOW_BITS = 20

/**
 * Default [Lz77Config.lengthBits], so a single match spans a few hundred bytes at most; see
 * [Lz77Config.maxMatch] for the exact reach.
 */
const val DEFAULT_LENGTH_BITS = 8

/**
 * Default [Lz77Config.maxChain].
 */
const val DEFAULT_MAX_CHAIN = 64

/**
 * Default [Lz77Config.repDistanceCount]: the eight most recent distinct match distances.
 */
const val DEFAULT_REP_DISTANCE_COUNT = 8

/**
 * Immutable set of parameters that bound the geometry of the
 * [LZ77](https://en.wikipedia.org/wiki/LZ77_and_LZ78) sliding window used by [Lz77Dictionary] and
 * tune its speed/quality trade-off.
 *
 * These parameters bound the reported matches and size the fields a token format codes them into.
 * [windowBits] fixes the history ring size, hence the maximum match distance, and [lengthBits]
 * bounds how long a single match may be. A [Lz77Decoder] must be constructed with the same
 * [windowSize] the matching used.
 *
 * The fields are independent: any combination is valid. Each one is bounded by its own limit:
 * [MAX_WINDOW_BITS], [MAX_LENGTH_BITS], [MAX_MIN_MATCH], [MAX_CHAIN] and [MAX_REP_DISTANCE_COUNT].
 *
 * @property windowBits base-2 logarithm of the history ring size, so the ring holds exactly
 * `2^windowBits` bytes and the furthest a match may reach back is [maxDistance] `= windowSize - 1`.
 * Capped at [MAX_WINDOW_BITS].
 * @property lengthBits controls the longest reportable match, see [maxMatch]. Capped at
 * [MAX_LENGTH_BITS].
 * @property minMatch shortest byte run that [Lz77Dictionary] will report as a match. Cannot fall
 * below [HASH_LENGTH], the width of the dictionary's hash key, which is also its default; capped at
 * [MAX_MIN_MATCH].
 * @property maxChain upper bound on how many candidate positions the dictionary inspects per
 * position; larger values improve match quality at a linear cost in time. Capped at [MAX_CHAIN].
 * @property repDistanceCount how many of the most recent distinct match distances a token format
 * keeps in its [RepDistances] list, so that a match repeating one of them is named by a short index
 * instead of a full distance field; `0` remembers none, which removes the rep field from the wire
 * altogether. Every match searches the list, so the cost is linear in this count; capped at
 * [MAX_REP_DISTANCE_COUNT].
 */
data class Lz77Config(
    val windowBits: Int = DEFAULT_WINDOW_BITS,
    val lengthBits: Int = DEFAULT_LENGTH_BITS,
    val minMatch: Int = HASH_LENGTH,
    val maxChain: Int = DEFAULT_MAX_CHAIN,
    val repDistanceCount: Int = DEFAULT_REP_DISTANCE_COUNT,
) : CompressionConfig {
    init {
        require(minMatch in HASH_LENGTH..MAX_MIN_MATCH) {
            "minMatch must be in $HASH_LENGTH..$MAX_MIN_MATCH but was $minMatch"
        }
        require(windowBits in 1..MAX_WINDOW_BITS) {
            "windowBits must be in 1..$MAX_WINDOW_BITS but was $windowBits"
        }
        require(lengthBits in 1..MAX_LENGTH_BITS) {
            "lengthBits must be in 1..$MAX_LENGTH_BITS but was $lengthBits"
        }
        require(maxChain in 1..MAX_CHAIN) { "maxChain must be in 1..$MAX_CHAIN but was $maxChain" }
        require(repDistanceCount in 0..MAX_REP_DISTANCE_COUNT) {
            "repDistanceCount must be in 0..$MAX_REP_DISTANCE_COUNT but was $repDistanceCount"
        }
    }

    /**
     * Size of the history ring in bytes, i.e. `2^windowBits`. Note it is **not** the largest match
     * distance: see [maxDistance].
     */
    val windowSize: Int get() = 1 shl windowBits

    /**
     * Longest match, in bytes, that [Lz77Dictionary] will report, spanning both the short and the
     * long length range a token format names.
     */
    val maxMatch: Int get() = minMatch + shortLengthCount(lengthBits) + (1 shl lengthBits) - 1

    /**
     * Largest match distance that can be reported: `windowSize - 1`.
     *
     * History lives in a ring of exactly [windowSize] bytes. A distance of [windowSize] would map
     * to the current position's own ring slot, so the reach is one byte short of the full ring.
     */
    val maxDistance: Int get() = windowSize - 1
}
