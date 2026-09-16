# 🌴 Palm

[![Build](https://github.com/penemue/palm/actions/workflows/build.yml/badge.svg)](https://github.com/penemue/palm/actions/workflows/build.yml)
[![JitPack](https://jitpack.io/v/penemue/palm.svg)](https://jitpack.io/#penemue/palm)
[![Apache License 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Pure Kotlin](https://img.shields.io/badge/100%25-kotlin-orange.svg)](https://kotlinlang.org)

A toolkit for building lossless compression methods in Kotlin from interchangeable parts. It has two
families: [LZ77](https://en.wikipedia.org/wiki/LZ77_and_LZ78), with a pluggable choice of parser
(greedy or one-byte lazy), and palmist, an adaptive next-byte context predictor.

## Palmist

Palmist has no back-references: every byte is coded as a literal, against the predictions of several
orders. When every prediction is wrong, the byte is coded through an inverted LZMA matched-literal
walk: LZMA keys its trees on a value the byte may equal, palmist keys them on the wrong predictions,
which the byte is known to differ from. Each bit goes through a tree keyed by the next bit of the
leading prediction still consistent with the prefix coded so far.

## Install

Any tag, branch, or commit of this repository is consumable as a Maven artifact via
[JitPack](https://jitpack.io/#penemue/palm); a branch is requested as `main-SNAPSHOT`.

```kotlin
// in Gradle project (Kotlin DSL)
repositories {
    maven(url = "https://jitpack.io")
}
dependencies {
    implementation("com.github.penemue:palm:main-SNAPSHOT")
}
```
```xml
<!-- in Maven project -->
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
<dependency>
    <groupId>com.github.penemue</groupId>
    <artifactId>palm</artifactId>
    <version>main-SNAPSHOT</version>
</dependency>
```

## Usage

```kotlin
import com.github.penemue.palm.CompressionProvider
import com.github.penemue.palm.lz77.Lz77Config

val method = CompressionProvider.find("lz77-lazy").create()

method.compress(input, output)
method.compress(input, output, Lz77Config(windowBits = 22)) // custom geometry
method.decompress(input, output)
```

`compress`/`decompress` work on `java.io` streams and do not close them. `Lz77Config` is optional;
`decompress` needs none because the geometry travels with the payload.

### Providers

| Provider id | Family | Modelling |
|---|---|---|
| `lz77-greedy` | LZ77 | greedy parser |
| `lz77-lazy` | LZ77 | one-byte lazy parser |
| `palmist` | palmist | several order predictors |

All three code their tokens with an adaptive range coder; `lz77-lazy` gives the best ratio.

## Results

Best provider per family and corpus:

| Corpus | Provider | Original | Encoded | Ratio |
|---|---|---:|---:|---:|
| [Canterbury](https://corpus.canterbury.ac.nz/) | `lz77-lazy` | 2,810,784 | 514,370 | 0.18 |
| [Canterbury](https://corpus.canterbury.ac.nz/) | `palmist` | 2,810,784 | 514,821 | 0.18 |
| [Silesia](https://sun.aei.polsl.pl/~sdeor/index.php?page=silesia) | `lz77-lazy` | 211,938,580 | 56,731,610 | 0.27 |
| [Silesia](https://sun.aei.polsl.pl/~sdeor/index.php?page=silesia) | `palmist` | 211,938,580 | 58,401,031 | 0.28 |

Full tables per provider and per file: [`BENCHMARK_RESULTS.md`](BENCHMARK_RESULTS.md).

## Build

```shell
./gradlew build
```

Requires JDK 25 to build; the compiled classes run on Java 8 or newer.
