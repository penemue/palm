# `com.github.penemue.palm.palmist`

An adaptive **next-byte prediction** compressor. Predictors of order 1 upwards each propose the byte
they expect next, and a byte one of them got right costs a fraction of a bit. A literal has neither a
length nor a distance — there are no back-references at all. The package plugs into the project's
`CompressionProvider` SPI as the `palmist` service.

Compression is split into two concerns — **parse** and **predict and encode the tokens** — mirroring
the `lz77` package. Prediction belongs to the coder: a token carries nothing but its byte value.

## Contents

| Type | Role |
|---|---|
| [`PalmistConfig`](PalmistConfig.kt) | Immutable geometry: `orderCount` (`DEFAULT_ORDER_COUNT`), bounded by `MIN_ORDER_COUNT` and `MAX_ORDER_COUNT`. |
| [`MultiOrderByteModel`](MultiOrderByteModel.kt) | The order tables with their confidence gates, exposing their distinct predictions in confidence order. |
| [`PalmistEncoder`](PalmistEncoder.kt) | `ByteArray.palmistEncode` — emits one literal per input byte. |
| [`PalmistDecoder`](PalmistDecoder.kt) | A `PalmistTokenConsumer` that writes replayed literals out as bytes. |
| [`PalmistTokenConsumer`](PalmistTokenConsumer.kt) | Sink interface: `literal(value)`, `finish`. |
| [`RangePalmistTokenConsumer`](RangePalmistTokenConsumer.kt) | Owns a prediction model and entropy-codes literals via [`range`](../range/README.md): a unary chain plus an escape model coded against every denied prediction. |
| [`PalmistTokenReader`](PalmistTokenReader.kt) | Decode-side inverse of the consumer; [`RangePalmistTokenReader`](RangePalmistTokenReader.kt) mirrors its prediction model and both range models. |
| [`PalmistCompressionProviders`](PalmistCompressionProviders.kt) | The SPI provider and the decode loop. |

## Pipeline

**Compress:** `palmistEncode` pushes one `literal` per input byte into the `PalmistTokenConsumer`,
ending with `finish`; the consumer predicts, codes and then learns the byte.
**Decompress:** the `PalmistTokenReader` predicts, decodes and learns each byte, and a
`PalmistDecoder` writes it out.

The two prediction models are never compared: each side drives its own from the bytes it has seen, so
they hold identical state at every position.

## Prediction

Each order keeps its own table of predicted bytes, addressed by that many preceding bytes. Orders 1
and 2 index their table directly, in 256 and 65536 slots; every deeper order is wider than its table,
so it folds the context through a Fibonacci hash and distinct contexts share slots. Each hashed table
is twice the size of the one above it, so the tables grow by one bit per order while the context they
key on grows by a whole byte. The deepest order supported reads the whole 8-byte history a `Long`
holds.

A slot is not overwritten by every byte that contradicts it. Each one carries a *confidence*, raised
whenever its prediction is right and spent to keep that prediction in place: a contradicting byte is
absorbed while confidence remains and replaces the prediction only once it runs out. Protection is
therefore **earned** — a prediction confirmed many times survives a burst of outliers, while an
unconfirmed one, in particular the initial byte of a slot no context has been seen in yet, is
replaced on the spot. That last case matters more than it looks: with a hashed context most contexts
occur exactly once, so a rule that made every replacement wait for a second sighting would leave
those slots predicting their initial byte forever.

Confidence saturates: its ceiling is how many contradicting bytes a fully confirmed prediction
survives, and it is a compile-time constant rather than a geometry field. Fixing it lets the counter
be packed two bits per slot, so a confidence array costs a quarter of the table beside it.

The predictions are presented as one list of **distinct** bytes — a value several orders agree on
appears once, carrying the highest confidence any of them holds for it — ordered by descending
confidence, ties keeping the deeper order ahead.

## Token coding

A literal is coded as one **unary chain** over the predictions: a bit per prediction, in their given
order, answering whether it is the input byte. A set bit ends the chain and the literal, so a
correctly predicted byte costs a fraction of a bit and nothing else is written. There is no separate
flag for "nothing matched": that outcome is what a chain reaching its end means, and only then does
the byte itself follow.

The chain's model is keyed by how many predictions there are, how the recent literals ended, the
leading prediction's confidence and the current run of consecutive hits; each step within a chain is
additionally keyed by the confidence of the prediction it offers, so a long-confirmed prediction and
a freshly installed one never share a probability.

A byte no order predicted is coded most significant bit first through a bit tree chosen by an
order-1 context, the previous literal byte. Its sub-trees are picked by the LZMA-style
*matched-literal* rule — but run against the predictions the chain has just denied, every one of
which the byte is known to differ from. A denied prediction stays live while its prefix equals the
prefix coded so far; a bit is coded in a *matched* sub-tree keyed by the next bit of the live one of
highest confidence and by a bucket of how many are still live, and in the plain sub-tree once none
is. Neither the predictions nor the adaptive models travel on the wire.

## Framing and invariants

Payloads start with the **common header** written by `CompressionMethod` (`PALM` magic, family byte,
original size as a VLQ). The palmist **body** is a geometry sub-header of one VLQ, `orderCount`,
followed by the coded literals. Key rules:

- The original size, not a terminator, ends decoding.
- The geometry comes from an untrusted header, so `PalmistConfig` bounds `orderCount` before the
  models are sized from it; each table's width is a compile-time constant, so the count alone bounds
  what the decoder allocates.
- The predictions are side information both sides already hold, so naming one costs a chain bit
  rather than a byte.
- The prediction and range models are **never stored**: the reader rebuilds them in lock-step.

## Usage

Go through the SPI rather than constructing internals directly:

```kotlin
val method = CompressionProvider.find("palmist").create()
method.compress(input, output)
method.decompress(input, output)
```

## Tuning

The hashed tables dominate the memory that grows with `orderCount`, and their width is a trade of
its own: a wider table forces fewer collisions, so a deeper order's prediction is confirmed more
often and more chains end on their first bit. Size keeps improving as those tables grow, so the
shipped width is a memory budget rather than a measured minimum.

`orderCount` does have an interior optimum. The orders fail in different places — a deep order
starves on data where a shallow one is confident, and the reverse on data with long exact context —
so an added order pays for itself as long as it predicts where the others miss. Past the optimum the
chain step it adds to every literal costs more than the order returns.

See `BENCHMARK_RESULTS.md` for the sizes this format reaches.
