# `com.github.penemue.palm.util`

Small primitives shared by the compression families: the unsigned reading of a byte, the number of
bits an integer occupies, and the multiplicative hash that the deeper palmist order tables and the
LZ77 match dictionary use to index themselves.

## Contents

| Declaration            | Role                                                                    |
|------------------------|-------------------------------------------------------------------------|
| `Byte.unsignedInt`     | Reads a byte as a raw eight-bit value in `0..255`.                      |
| `Int.bitLength()`      | Counts the bits a value occupies, up to its highest set bit.            |
| `Long.fibonacciHash()` | Folds a key into a table slot of a given bit count.                     |
