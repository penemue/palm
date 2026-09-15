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

import java.util.ServiceLoader

/**
 * Entry point for a named compression implementation, discovered through Java [ServiceLoader].
 *
 * Provider identifiers are stable configuration values and are not stored in compressed payloads.
 */
interface CompressionProvider {
    /**
     * Unique among the loaded providers.
     */
    val id: String

    /**
     * Every call returns an instance independent of the previously created ones.
     */
    fun create(): CompressionMethod

    companion object {
        /**
         * Blank or duplicate identifiers are rejected.
         */
        fun load(classLoader: ClassLoader = defaultClassLoader()): List<CompressionProvider> {
            val providers = ServiceLoader.load(CompressionProvider::class.java, classLoader).toList()
            val byId = mutableMapOf<String, CompressionProvider>()
            for (provider in providers) {
                val id = provider.id
                require(id.isNotBlank()) {
                    "Compression provider id must not be blank: ${provider.className}"
                }
                byId.putIfAbsent(id, provider)?.let { previous ->
                    throw IllegalArgumentException(
                        "Duplicate compression provider id '$id': ${previous.className} and ${provider.className}",
                    )
                }
            }
            return byId.values.toList()
        }

        /**
         * @throws IllegalArgumentException when the identifier is unknown or duplicated.
         */
        fun find(id: String, classLoader: ClassLoader = defaultClassLoader()): CompressionProvider {
            val providers = load(classLoader)
            return providers.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException(
                    "Unknown compression provider id '$id'; available ids: " +
                        providers.joinToString { it.id },
                )
        }

        private fun defaultClassLoader(): ClassLoader =
            Thread.currentThread().contextClassLoader ?: CompressionProvider::class.java.classLoader

        private val CompressionProvider.className: String get() = javaClass.name
    }
}
