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
package com.github.penemue.palm.util

/**
 * Nearest odd value to `2^64 / φ` with the golden ratio `φ`, reinterpreted as a signed [Long].
 */
private const val FIBONACCI_HASH_MULTIPLIER = -0x61c8864680b583ebL

/**
 * Widest table [fibonacciHash] addresses. A slot index is an [Int], so the sign bit is out of reach.
 */
internal const val MAX_SLOT_BITS = Int.SIZE_BITS - 1

/**
 * Folds this key into a table slot in `0 until 2^slotBits` by
 * [Fibonacci hashing](https://en.wikipedia.org/wiki/Hash_function#Fibonacci_hashing), spreading a
 * key too wide for an [Int].
 *
 * The key is multiplied by an odd constant derived from the golden ratio, letting the product wrap
 * the 64-bit word; the top of that product depends on every bit of the key, so keeping its high
 * [slotBits] bits spreads keys that differ in a single low bit far apart.
 *
 * @param slotBits base-2 logarithm of the table size, in `1..`[MAX_SLOT_BITS]; a wider one turns the
 * index negative.
 * @return a slot index needing no further masking.
 */
internal fun Long.fibonacciHash(slotBits: Int): Int =
    ((this * FIBONACCI_HASH_MULTIPLIER) ushr (Long.SIZE_BITS - slotBits)).toInt()
