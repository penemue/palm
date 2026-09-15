# `com.github.penemue.palm.vlq`

Unsigned [LEB128](https://en.wikipedia.org/wiki/LEB128) variable-length quantity (VLQ) helpers for
`java.io` streams. Small non-negative integers cost few bytes, so every payload header stores its
quantities this way instead of in fixed 4- or 8-byte fields: the common header spends it on the
original size, and each family's body sub-header on its own geometry.

## Contents

| Function | Role |
|---|---|
| `OutputStream.writeVarLong` / `writeVarInt` | Write a non-negative `Long` / `Int` as LEB128. |
| `InputStream.readVarLong` / `readVarInt` | Read back a value written by the matching writer. |

## Format

The value is split into 7-bit groups written least-significant group first. Every byte but the last
sets its high (continuation) bit, so the reader knows where the value ends. `0` takes one byte; any
value below `2^63` takes at most nine.

Only non-negative values are supported: the writers reject a negative argument, and the readers
reject a stream that ends mid-value, spans more than ten groups, or overflows a signed `Long` (or,
for `readVarInt`, `Int.MAX_VALUE`).

## Usage

```kotlin
output.writeVarLong(originalSize)   // 1..9 bytes, not a fixed 8
val size = input.readVarLong()
```
