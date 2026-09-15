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

import com.github.penemue.palm.util.unsignedInt

/**
 * Encodes this byte array as raw next-byte-prediction tokens.
 *
 * Every input byte becomes one literal, in input order, and the stream is terminated by exactly one
 * [PalmistTokenConsumer.finish] call. Neither prediction, token packing nor entropy coding is
 * performed here.
 *
 * @receiver input bytes to encode.
 * @param consumer destination for the literal tokens.
 */
fun ByteArray.palmistEncode(consumer: PalmistTokenConsumer) {
    for (byte in this) consumer.literal(byte.unsignedInt)
    consumer.finish()
}
