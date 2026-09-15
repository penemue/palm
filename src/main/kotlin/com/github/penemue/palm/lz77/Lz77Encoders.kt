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

import com.github.penemue.palm.util.bitLength
import com.github.penemue.palm.util.unsignedInt

/**
 * Encodes this byte array as raw [LZ77](https://en.wikipedia.org/wiki/LZ77_and_LZ78) tokens using a
 * greedy parsing strategy.
 *
 * At each input position, the function asks an [Lz77Dictionary] for the longest match, emits that
 * match when one exists, and otherwise emits one literal. A match returned by the dictionary never
 * overlaps the current position, so this function extends it against the receiver, which admits
 * repeated sequences whose final match length exceeds their distance.
 *
 * The emitted stream is terminated by exactly one [Lz77TokenConsumer.finish] call. Neither token
 * packing nor entropy coding is performed.
 *
 * Each emitted match makes its distance the most recently used one in [repDistances], right after
 * the consumer has coded that match, so a repeat is named against the distances of the *earlier*
 * matches.
 *
 * @receiver input bytes to encode.
 * @param consumer destination for literal and match tokens; its [Lz77TokenConsumer.config]
 * configures match finding and token limits.
 * @param repDistances the operation's list of recent match distances; the consumer queries the very
 * same instance.
 */
fun ByteArray.lz77GreedyEncode(consumer: Lz77TokenConsumer, repDistances: RepDistances) {
    val config = consumer.config
    val dictionary = Lz77Dictionary(config)
    var pos = 0
    while (pos < size) {
        val match = dictionary.longestMatch(this, pos, size - pos)
        val length = if (match.isMatch) {
            val extendedLength = extendedLength(pos, match, config.maxMatch)
            consumer.match(extendedLength, match.distance, this[pos + extendedLength - 1].unsignedInt)
            repDistances.remember(match.distance)
            dictionary.advanceMatch(extendedLength, match.distance)
            extendedLength
        } else {
            consumer.literal(this[pos].unsignedInt)
            dictionary.advanceLiteral(this[pos])
            1
        }
        pos += length
    }
    consumer.finish()
}

/**
 * Encodes this byte array as raw [LZ77](https://en.wikipedia.org/wiki/LZ77_and_LZ78) tokens using a
 * one-byte lazy parsing strategy.
 *
 * When a match exists at the current position, the function looks ahead: it tentatively consumes
 * one literal and searches again at the next position, then keeps whichever of the two matches
 * promises the cheaper stream. If no match exists at the current position, it emits one literal
 * without looking ahead.
 *
 * The lookahead is skipped outright when the first match already repeats one of the distances in
 * [repDistances]. Otherwise the literal and the second match are kept when that second, extended
 * match is
 *  - more than one byte longer, or
 *  - exactly one byte longer and its distance needs fewer bits to spell out, or
 *  - no shorter and repeating any remembered distance, or
 *  - one byte shorter and repeating the most recent distance.
 *
 * In every other case the first match is emitted.
 *
 * Matches are extended against the receiver, which admits overlapping repeated sequences. The
 * emitted stream is terminated by exactly one [Lz77TokenConsumer.finish] call. Neither token
 * packing nor entropy coding is performed.
 *
 * Each emitted match makes its distance the most recently used one in [repDistances], right after
 * the consumer has coded that match, so a repeat is named against the distances of the *earlier*
 * matches.
 *
 * @receiver input bytes to encode.
 * @param consumer destination for literal and match tokens; its [Lz77TokenConsumer.config]
 * configures match finding and token limits.
 * @param repDistances the operation's list of recent match distances; the consumer queries the very
 * same instance.
 */
fun ByteArray.lz77LazyEncode(consumer: Lz77TokenConsumer, repDistances: RepDistances) {
    val config = consumer.config
    val dictionary = Lz77Dictionary(config)
    var pos = 0
    while (pos < size) {
        val firstMatch = dictionary.longestMatch(this, pos, size - pos)
        if (!firstMatch.isMatch) {
            consumer.literal(this[pos].unsignedInt)
            dictionary.advanceLiteral(this[pos])
            pos++
            continue
        }
        val firstLen = extendedLength(pos, firstMatch, config.maxMatch)
        val firstDistance = firstMatch.distance
        // Move the window on before searching at pos + 1: a reported distance is measured from the
        // dictionary's own position, not from the pattern offset it is handed.
        dictionary.advanceLiteral(this[pos])
        if (!repDistances.has(firstDistance)) {
            val secondMatch = dictionary.longestMatch(this, pos + 1, size - pos - 1)
            if (secondMatch.isMatch) {
                val secondLen = extendedLength(pos + 1, secondMatch, config.maxMatch)
                val secondDistance = secondMatch.distance
                val useSecond = secondLen > firstLen + 1 ||
                        (secondLen == firstLen + 1 && secondDistance.bitLength() < firstDistance.bitLength()) ||
                        run {
                            val repIndex = repDistances.indexOf(secondDistance)
                            (secondLen >= firstLen && repIndex >= 0) || (secondLen == firstLen - 1 && repIndex == 0)
                        }
                if (useSecond) {
                    consumer.literal(this[pos].unsignedInt)
                    // The second match starts one literal later, so its final byte sits at
                    // (pos + 1) + secondLen - 1.
                    consumer.match(secondLen, secondDistance, this[pos + secondLen].unsignedInt)
                    dictionary.advanceMatch(secondLen, secondDistance)
                    repDistances.remember(secondDistance)
                    pos += secondLen + 1
                    continue
                }
            }
        }
        consumer.match(firstLen, firstDistance, this[pos + firstLen - 1].unsignedInt)
        // The byte at pos entered history as that speculative literal, so the match has one byte
        // fewer left to record.
        dictionary.advanceMatch(firstLen - 1, firstDistance)
        repDistances.remember(firstDistance)
        pos += firstLen
    }
    consumer.finish()
}

private fun ByteArray.extendedLength(
    pos: Int,
    match: Lz77Dictionary.Match,
    maxMatch: Int,
): Int {
    val distance = match.distance
    val limit = pos + minOf(maxMatch, size - pos)
    var end = pos + match.length
    while (end < limit && this[end] == this[end - distance]) {
        end++
    }
    return end - pos
}
