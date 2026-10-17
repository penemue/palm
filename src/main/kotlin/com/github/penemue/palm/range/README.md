# `com.github.penemue.palm.range`

An adaptive binary [range coder](https://en.wikipedia.org/wiki/Range_coding) in the style of the
LZMA arithmetic coder. It codes one *binary* decision at a time, each against its own adaptive
probability, so a caller can spend a fraction of a bit on a skewed bit instead of a whole one.

It compresses nothing on its own: it is the entropy back-end a model drives. The model owns the
probabilities and the contexts; this package only turns `(probability, bit)` into bytes and back.

## Contents

| Type | Role |
|---|---|
| [`RangeFormat`](BitModel.kt) | Probability precision, renormalization threshold and warm-up rate selected by a stream format version. |
| [`BitModel`](BitModel.kt) | `BitModel(size, adaptShift, format)` — `size` adaptive bit probabilities, each adapting at a rate set by its own age. |
| [`RangeEncoder`](RangeEncoder.kt) | `encodeBit`, `encodeBitTree`, `encodeRawBits`, then `finish()`; carry-aware, writes bytes incrementally. |
| [`RangeDecoder`](RangeDecoder.kt) | `decodeBit`, `decodeBitTree`, `decodeRawBits`; the exact inverse, primed from the encoder's flush bytes. |
| [`MatchedBitTreeModel`](MatchedBitTreeModel.kt) | A value coded against a predicted one: matched trees keyed by the predicted bit plus a plain tree, sharing one `BitModel` across every context. |
| [`excludedLiteralModel`](ExcludedLiteralModel.kt) | A `BitModel` sized for a byte coded against several values it is known to differ from: matched trees keyed by the leading live value's next bit and by how many stay live. |

## Contract

A `BitModel` holds `size` independent probabilities, each the chance of a zero bit on the scale
selected by its `RangeFormat`, all starting at one half. The caller chooses which entry applies to
each decision — that index **is** the context (an order-1 byte, a bit-tree node, a flag, …).

- `encodeBit(model, index, bit)` splits the range by that probability, emits the choice, then nudges
  the probability toward `bit` at the rate that context has reached.
- `decodeBit(model, index)` reproduces the bit and applies the **same** nudge.

That rate is not fixed for the life of the stream. A context starts at the warm-up rate of its
`RangeFormat` and slows by one step whenever its observation count doubles, settling at the model's
`adaptShift`, which is therefore the slowest rate any of its contexts reaches rather than the rate
they all use. A young context leaves one half within a few bits instead of crawling away from it;
the price is a counter beside every probability.

Because both directions read and update the probabilities identically, an encoder and a decoder
that visit the same indices in the same order stay in lock-step **with no table on the wire** — the
whole model, observation counts included, is implicit. Encode order is therefore significant: decode
must mirror it exactly.

Instances are stateful and **not thread-safe**: one stream per instance. The encoder emits five
flush bytes on `finish()`; the decoder consumes them on construction (the first is always zero).
Neither closes the caller's stream, and `finish()` writes those last bytes without flushing the
destination, so a caller that buffers must flush it itself.

## Coding a value wider than one bit

A symbol is spelled out most significant bit first, and the coder offers four ways to spend those
bits:

- `encodeBitTree(model, base, value, bitCount)` walks a complete binary tree whose leaves are the
  `2^bitCount` possible values, coding each bit against the node the path has reached, so every
  prefix owns its own probability. `base` is where the caller's context starts in the model.
- `encodeMatchedBitTree(model, context, value, predicted)`, an extension declared beside
  `MatchedBitTreeModel` rather than on the encoder itself, codes a value against one both sides
  already hold: while the coded prefix still equals the prediction's, bits go through a *matched*
  tree keyed by the next predicted bit, the rest through the plain tree. The prediction biases the
  model without being transmitted.
- `encodeExcludedLiteral(model, context, value, excluded, excludedCount)`, declared beside
  `excludedLiteralModel`, is the same walk run against a set of values the byte is known NOT to be:
  an excluded value stays live while its prefix matches the one coded so far, the matched tree is
  keyed by the leading live value's next bit and by a bucket of how many are still live, and the
  plain tree takes over once none does.
- `encodeRawBits(value, count)` spends whole bits on equiprobable data, touching no model at all —
  the escape hatch for a field with nothing left to predict.

Each has an exact inverse on the decoder, reached the same way, and the two must be called in the
same order. Both matched walks carry a width limit: `MatchedBitTreeModel` codes a value of at most
`MAX_MATCHED_BIT_COUNT` bits, and `encodeExcludedLiteral` tracks liveness in the bits of one `Int`,
so it accepts at most `MAX_EXCLUDED_COUNT` excluded values.

## Usage

```kotlin
val model = BitModel(256)                    // one context per, say, previous byte

val encoder = RangeEncoder(output)
encoder.encodeBit(model, context, bit)       // repeat for every decision, in order
encoder.finish()                             // flush the coder; does not close output

val replayed = BitModel(256)                 // same layout, again starting at one half
val decoder = RangeDecoder(input)
val bit = decoder.decodeBit(replayed, context)
```

## Tuning

`RangeFormat` carries the probability precision, the renormalization threshold and the warm-up rate.
Compression-family format versions retain the values they were released with, so later tuning does
not change how an older payload is decoded. The current defaults are 13 probability bits, a `2^24`
threshold and a warm-up shift of 2. Wider precision gives a skewed bit a finer floor probability;
the `Short` storage and the 32-bit range multiplication bound it from above, at
`MAX_PROBABILITY_BITS`, while `MIN_PROBABILITY_BITS` keeps the scale wide enough for the fastest
adaptation rate. The threshold runs from `2^probabilityBits`, the
narrowest range every probability still splits into two non-empty parts, up to `MAX_RANGE_TOP`, the
widest one a byte shift keeps inside 32 bits.

The adaptation rate is **per model**: `BitModel(size, adaptShift)`, defaulting to
`DEFAULT_PROBABILITY_ADAPT_SHIFT`. It runs from the format's warm-up rate up to one shift below the
probability bits of the format the model was given, which on the default format is
`MAX_PROBABILITY_ADAPT_SHIFT`; a coarser format admits only faster rates, since a shift as wide as
the scale would leave a probability at one half unmoved. A smaller shift adapts faster, tracking a
regime switch but also noise; a larger one is inertial and suits long homogeneous data. Different
fields of one stream can therefore adapt at different speeds.

The warm-up rate is not only the lower bound on that argument: it is the rate every context starts
from, so it governs how aggressively a fresh context moves and how long the ladder up to
`adaptShift` is. `MIN_PROBABILITY_ADAPT_SHIFT` bounds it from below.

Every shipped compression provider drives this coder:
[`palmist`](../palmist/README.md) with a unary prediction chain plus an order-1 escape byte model, and
[`lz77`](../lz77/README.md) with its selector, literal, length and distance models.
