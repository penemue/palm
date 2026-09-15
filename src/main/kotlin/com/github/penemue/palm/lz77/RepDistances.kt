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

/**
 * The most recently used **distinct** match distances, kept most recent first.
 *
 * A party that tracks the last few match distances can refer to a repeat by its position in this
 * list — a small index — instead of spelling the distance out again. Nothing about the list travels
 * on the wire: both sides of a stream see the same matches, so they arrive at the same list on their
 * own.
 *
 * The list is ordered by *recency*, not by value, following the
 * [move-to-front](https://en.wikipedia.org/wiki/Move-to-front_transform) heuristic. A slot that has
 * never been filled holds `0`, which no real distance ever is, so an unfilled slot never matches a
 * lookup.
 *
 * A [size] of `0` is legal and remembers nothing: [indexOf] always reports a miss.
 *
 * ### One list per operation, advanced by the coding loop
 * Exactly one instance serves a whole compression or decompression run: the token consumer or
 * reader coding the distance fields only ever *queries* it, and the loop driving it calls [remember]
 * once per match, right after the match has been coded. A distance field is therefore coded against
 * the distances of the *earlier* matches, and both sides of a stream advance the list at the same
 * point.
 *
 * Instances are **not** thread-safe.
 *
 * @param count how many distinct distances to keep; `0` remembers none.
 */
class RepDistances(count: Int) {

    private val distances = IntArray(count)

    /**
     * Capacity, not the number of distances actually seen.
     */
    val size: Int get() = distances.size

    /**
     * Returns how recently [distance] was last remembered: `0` for the distance of the latest
     * match, `1` for the distinct distance used before it, and so on, or `-1` when it is not among
     * the remembered distances.
     */
    fun indexOf(distance: Int): Int = distances.indexOf(distance)

    /**
     * Whether [distance] is among the remembered ones, for a caller that needs no position.
     */
    fun has(distance: Int): Boolean = distances.contains(distance)

    /**
     * Returns the distance remembered at [index], counted from the most recent one.
     *
     * @param index position in `0 until size`, as reported by [indexOf].
     */
    operator fun get(index: Int): Int = distances[index]

    /**
     * Records [distance] as the most recently used one: a distance already remembered is promoted
     * from where it sat, and a fresh one enters at the front, pushing the least recently used
     * distance out of the list.
     *
     * @param distance the distance of the match just coded.
     */
    fun remember(distance: Int) {
        if (distances.isEmpty()) {
            return
        }
        val known = indexOf(distance)
        // A fresh distance enters as if it sat one past the end, so the last entry falls out.
        var i = if (known < 0) distances.size - 1 else known
        while (i > 0) {
            distances[i] = distances[i - 1]
            i--
        }
        distances[0] = distance
    }
}
