# `com.github.penemue.palm.lz77`

An [LZ77](https://en.wikipedia.org/wiki/LZ77_and_LZ78) byte compressor: it replaces repeated byte
runs with `(length, distance)` back-references and codes the result with an adaptive binary range
coder. The package plugs into the project's `CompressionProvider` SPI, so each variant is a
discoverable `ServiceLoader` service.

Compression is split into three concerns — **find matches**, **choose which matches to emit**, and
**encode the tokens** — so the token format and the parser evolve independently.

## Contents

| Type | Role |
|---|---|
| [`Lz77Config`](Lz77Config.kt) | Immutable geometry: `windowBits`, `lengthBits`, `minMatch`, `maxChain`, `repDistanceCount` (defaults `20 / 8 / 5 / 64 / 8`), each capped by its own `MAX_*` limit, `minMatch` additionally floored at `HASH_LENGTH`. |
| [`Lz77Dictionary`](Lz77Dictionary.kt) | Hash-chained cyclic window that finds the longest match at a position, keyed by the leading `HASH_LENGTH = 5` bytes; its window carries a mirrored tail so a wrapping run compares as a flat range. |
| [`Lz77Encoders`](Lz77Encoders.kt) | `ByteArray.lz77GreedyEncode` and `lz77LazyEncode` (one-byte lazy) parsers. |
| [`Lz77TokenConsumer`](Lz77TokenConsumer.kt) | Sink the parser emits into: `literal`, `match`, `finish`. |
| [`RepDistances`](RepDistances.kt) | Move-to-front list of the last `repDistanceCount` distinct match distances; one instance per operation, advanced by the coding loop. |
| [`RangeLz77TokenConsumer`](RangeLz77TokenConsumer.kt) | Adaptive [range coder](../range/README.md): order-1 literals, run-bucketed selector, range-split lengths keyed by the previous match's length, run-bucketed rep bit, LZMA-style distance slots. |
| [`Lz77TokenReader`](Lz77TokenReader.kt) | Decode-side counterpart of the consumer; [`RangeLz77TokenReader`](RangeLz77TokenReader.kt) mirrors the range models. |
| [`Lz77Decoder`](Lz77Decoder.kt) | Replays literals and back-references into the output ring. |
| [`Lz77CompressionProviders`](Lz77CompressionProviders.kt) | The SPI providers, the LZ77 body's geometry sub-header, and the token decoder. |

## Pipeline

**Compress:** parser (`lz77GreedyEncode` / `lz77LazyEncode`) drives a `Lz77Dictionary` and pushes
`literal`/`match` tokens into `RangeLz77TokenConsumer`, which drives a `RangeEncoder`.
**Decompress:** `decodeTokens` reads each token through `RangeLz77TokenReader` and replays it into
`Lz77Decoder`, which reconstructs the bytes.

Dictionary and parser are decoupled from the token format: greedy vs lazy changes *which* matches
are emitted; the token consumer changes *how* they are encoded. Match search dominates encode time,
so the dictionary is where speed work pays off and the token format is where size work does.

## Providers

Two built-in providers share the range-coded token format and differ only in the parser:

- `lz77-greedy`
- `lz77-lazy` — the smaller of the two (see `BENCHMARK_RESULTS.md`)

The lazy parser weighs the match at the current position against the one a literal later. It skips
that lookahead when the first match already repeats a remembered distance, and otherwise prefers the
second match when it is more than one byte longer, exactly one byte longer at a distance of fewer
significant bits, no shorter while repeating a remembered distance, or one byte shorter while
repeating the most recent distance.

The [`palmist`](../palmist/README.md) package mirrors this consumer/reader split for a family that
codes literals against a next-byte prediction instead of back-references.

## Token format

The **distance** field opens with a **rep flag**: a match whose distance is still among the last
`repDistanceCount` distinct match distances names it by its index in that list instead of spelling
the distance out. Flag and index are both coded in contexts bucketed by the match run preceding this
match, the index as an adaptive unary chain with one probability per position, whose last position
needs no bit at all — it is implied by the positions before it. A fresh distance is split LZMA-style
into a slot — the significant-bit count of `distance - 1`, coded through a bit tree — and the
mantissa below the leading one, whose high bits are equiprobable and whose lowest
`DISTANCE_ALIGN_BITS` bits are modeled adaptively per position. Every field names its own
adaptation rate: `REP_MODEL_ADAPT_SHIFT` for the rep flag and index,
`DISTANCE_SLOT_MODEL_ADAPT_SHIFT` for the slot tree, `DISTANCE_ALIGN_MODEL_ADAPT_SHIFT` for the
align bits, and one constant each for the selector, the literal and the length. Nothing about the
rep list travels on the wire.

An operation runs on exactly one `RepDistances`, created by `Lz77CompressionMethod` and handed to
the token consumer or reader. Those only *query* it; the loop driving them — the parser while
compressing, `decodeTokens` while decompressing — advances it once per match, right after the match
has been coded. A distance field is therefore coded against the distances of the earlier matches,
and both sides reach that state at the same point. With `repDistanceCount = 0` no flag is written at
all.

The **literal** field is conditioned on the previous *output* byte, so a literal following a match is
coded against the byte that match ended on rather than a stale one. A 256-byte table indexed by that
same byte holds the byte that last followed it; that *prediction* selects a matched sub-tree instead
of being named on the wire, and only literals teach the table.

The **length** field codes `length - minMatch`: its `2^min(SHORT_LENGTH_BITS, lengthBits)` lowest
values form a short range coded through a shallow bit tree, the rest a long range spanning
`lengthBits`, with a range flag between them. The flag and the short tree are contextualized by the
previous match's length group (`LENGTH_PREV_GROUPS` of them, a logarithmic scale widening as lengths
grow) as well as by the after-literal/after-match distinction the long tree uses alone.
`Lz77Config.maxMatch` covers both ranges, so every length the format can name is a length the
dictionary may report.

## Framing and invariants

Payloads start with the **common header** written by `CompressionMethod` (`PALM` magic, a family
byte, and the original size as a VLQ). The LZ77 **body** then adds its own sub-header — the
`Lz77Config` geometry (`windowBits`, `lengthBits`, `minMatch`, `repDistanceCount`, each a VLQ) —
followed by the coded token stream. Key rules:

- The original size, not a terminator, ends decoding — the coder's trailing flush bytes are ignored.
- The geometry a payload carries is self-describing: `compress` accepts an optional `Lz77Config`
  (falling back to the defaults), writes that geometry into the body, and `decompress` takes no
  config — it rebuilds the geometry from the sub-header. A hostile header is bounded by
  `Lz77Config` itself, which rejects every out-of-range field — `windowBits` among them — before the
  decoder allocates its `2^windowBits` ring. `maxChain` is a search knob only, so it never travels
  and the decoder keeps its default.
- Both variants share one family byte and one body layout, so they share the `Lz77CompressionMethod`
  subclass; the family byte lets a decoder reject a payload from an unrelated method instead of
  misreading it.
- A decoded match is validated against the geometry: its distance must be reachable and its length
  must not overshoot the original size.
- Compression methods must not close caller-owned streams.

## Usage

Go through the SPI rather than constructing internals directly:

```kotlin
val method = CompressionProvider.find("lz77-lazy").create()
method.compress(input, output)                       // default geometry; does not close streams
method.compress(input, output, Lz77Config(windowBits = 22)) // custom geometry for this operation
method.decompress(input, output)                     // geometry is read back from the payload
```

## Tuning

The format's knobs (`DISTANCE_ALIGN_BITS`, `SELECTOR_RUN_BUCKETS`, `REP_RUN_BUCKETS`,
`REP_INDEX_RUN_BUCKETS`, `SHORT_LENGTH_BITS`, `LENGTH_PREV_GROUPS`, the adapt shifts) are top-level
constants in `RangeLz77TokenConsumer.kt`, read by the reader as well.
`BENCHMARK_RESULTS.md` holds the current numbers.

`Lz77Config.maxChain` is the speed knob: it caps how many chain candidates a search inspects, and
match search is the bulk of encode time. Lazy parsing is the slower parser because it runs a second
search at every match whose distance is not already remembered. `Lz77Config.repDistanceCount` trades
a longer rep index chain (and a longer scan per match on both sides) for more distances that can be
named instead of spelled out. Both carry sanity caps (`MAX_CHAIN`, `MAX_REP_DISTANCE_COUNT`).
