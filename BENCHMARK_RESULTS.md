# Compression Benchmark Results

## Contents

- [LZ77](#lz77)
  - [About these results](#about-these-results)
  - [Corpus totals](#corpus-totals)
  - [Per-file tables](#per-file-tables)
    - [`lz77-greedy`](#lz77-greedy)
    - [`lz77-lazy`](#lz77-lazy)
- [Palmist](#palmist)
  - [About these results](#about-these-results-1)
  - [Corpus totals](#corpus-totals-1)
  - [Per-file tables](#per-file-tables-1)

## LZ77

### About these results

- Configuration: `Lz77Config(windowBits=20, lengthBits=8, minMatch=5, maxChain=64,
  repDistanceCount=8)`, with the match dictionary keyed on the `HASH_LENGTH = 5` leading bytes of a
  match, which is also the shortest match it reports.
- Both providers below were measured in the same run on the current match finder, so the rows are
  directly comparable with each other.
- Corpora: the complete [Canterbury Corpus](https://corpus.canterbury.ac.nz/) and
  [Silesia Compression Corpus](https://sun.aei.polsl.pl/~sdeor/index.php?page=silesia).
- Each corpus member is compressed independently through its `CompressionProvider` (the public SPI),
  so every reported size is the complete payload, **including** the SPI framing header (a magic
  number, a family byte, and the VLQ-coded original size and LZ77 geometry - `windowBits`,
  `lengthBits`, `minMatch` and `repDistanceCount`) and the range coder's final flush.
- This is deliberately a size/ratio benchmark, not a CPU benchmark. "Packed bits per byte" is
  `encodedBytes * 8 / originalBytes`; "packed ratio" is `encodedBytes / originalBytes`. Neither is a
  count of meaningful token bits.
- Date: 2026-11

The two providers differ only in the parser: `lz77-greedy` is greedy, `lz77-lazy` is
one-byte lazy.

The token format and the two parsing rules are described in
[`lz77/README.md`](src/main/kotlin/com/github/penemue/palm/lz77/README.md); the adaptation schedule
both families share is described in
[`range/README.md`](src/main/kotlin/com/github/penemue/palm/range/README.md).

### Corpus totals

#### Canterbury Corpus (2,810,784 bytes)

| Provider | Encoded bytes | Packed bits/byte | Packed ratio | vs greedy |
|---|---:|---:|---:|---:|
| `lz77-greedy` | 522,228 | 1.4864 | 0.1858 | - |
| `lz77-lazy` | 514,370 | 1.4640 | 0.1830 | -1.50% |

#### Silesia Compression Corpus (211,938,580 bytes)

| Provider | Encoded bytes | Packed bits/byte | Packed ratio | vs greedy |
|---|---:|---:|---:|---:|
| `lz77-greedy` | 57,839,483 | 2.1833 | 0.2729 | - |
| `lz77-lazy` | 56,731,610 | 2.1414 | 0.2677 | -1.92% |

Lazy parsing wins both corpora overall, but not every file: on Canterbury the greedy parser codes
`kennedy.xls` in 39,539 bytes against the lazy 40,367, because its recurring distances reward taking
each match as soon as it is found.

### Per-file tables

#### `lz77-greedy`

**Canterbury**

| File | Original bytes | Encoded bytes | Packed bits/byte | Packed ratio |
|---|---:|---:|---:|---:|
| alice29.txt | 152,089 | 51,146 | 2.6903 | 0.3363 |
| asyoulik.txt | 125,179 | 46,359 | 2.9627 | 0.3703 |
| cp.html | 24,603 | 7,820 | 2.5428 | 0.3178 |
| fields.c | 11,150 | 3,168 | 2.2730 | 0.2841 |
| grammar.lsp | 3,721 | 1,291 | 2.7756 | 0.3469 |
| kennedy.xls | 1,029,744 | 39,539 | 0.3072 | 0.0384 |
| lcet10.txt | 426,754 | 128,854 | 2.4155 | 0.3019 |
| plrabn12.txt | 481,861 | 178,019 | 2.9555 | 0.3694 |
| ptt5 | 513,216 | 52,367 | 0.8163 | 0.1020 |
| sum | 38,240 | 11,836 | 2.4762 | 0.3095 |
| xargs.1 | 4,227 | 1,829 | 3.4616 | 0.4327 |
| **TOTAL** | **2,810,784** | **522,228** | **1.4864** | **0.1858** |

**Silesia**

| File | Original bytes | Encoded bytes | Packed bits/byte | Packed ratio |
|---|---:|---:|---:|---:|
| dickens | 10,192,446 | 3,312,597 | 2.6000 | 0.3250 |
| mozilla | 51,220,480 | 16,231,935 | 2.5352 | 0.3169 |
| mr | 9,970,564 | 2,963,256 | 2.3776 | 0.2972 |
| nci | 33,553,445 | 2,429,659 | 0.5793 | 0.0724 |
| ooffice | 6,152,192 | 2,740,650 | 3.5638 | 0.4455 |
| osdb | 10,085,684 | 3,076,659 | 2.4404 | 0.3051 |
| reymont | 6,627,202 | 1,691,224 | 2.0416 | 0.2552 |
| samba | 21,606,400 | 4,556,107 | 1.6869 | 0.2109 |
| sao | 7,251,944 | 4,874,108 | 5.3769 | 0.6721 |
| webster | 41,458,703 | 10,503,627 | 2.0268 | 0.2534 |
| xml | 5,345,280 | 527,949 | 0.7902 | 0.0988 |
| x-ray | 8,474,240 | 4,931,712 | 4.6557 | 0.5820 |
| **TOTAL** | **211,938,580** | **57,839,483** | **2.1833** | **0.2729** |

#### `lz77-lazy`

**Canterbury**

| File | Original bytes | Encoded bytes | Packed bits/byte | Packed ratio |
|---|---:|---:|---:|---:|
| alice29.txt | 152,089 | 50,498 | 2.6562 | 0.3320 |
| asyoulik.txt | 125,179 | 45,810 | 2.9276 | 0.3660 |
| cp.html | 24,603 | 7,761 | 2.5236 | 0.3154 |
| fields.c | 11,150 | 3,122 | 2.2400 | 0.2800 |
| grammar.lsp | 3,721 | 1,283 | 2.7584 | 0.3448 |
| kennedy.xls | 1,029,744 | 40,367 | 0.3136 | 0.0392 |
| lcet10.txt | 426,754 | 126,934 | 2.3795 | 0.2974 |
| plrabn12.txt | 481,861 | 174,694 | 2.9003 | 0.3625 |
| ptt5 | 513,216 | 50,554 | 0.7880 | 0.0985 |
| sum | 38,240 | 11,527 | 2.4115 | 0.3014 |
| xargs.1 | 4,227 | 1,820 | 3.4445 | 0.4306 |
| **TOTAL** | **2,810,784** | **514,370** | **1.4640** | **0.1830** |

**Silesia**

| File | Original bytes | Encoded bytes | Packed bits/byte | Packed ratio |
|---|---:|---:|---:|---:|
| dickens | 10,192,446 | 3,256,727 | 2.5562 | 0.3195 |
| mozilla | 51,220,480 | 15,835,143 | 2.4733 | 0.3092 |
| mr | 9,970,564 | 2,969,200 | 2.3824 | 0.2978 |
| nci | 33,553,445 | 2,280,306 | 0.5437 | 0.0680 |
| ooffice | 6,152,192 | 2,695,996 | 3.5057 | 0.4382 |
| osdb | 10,085,684 | 3,034,107 | 2.4067 | 0.3008 |
| reymont | 6,627,202 | 1,625,381 | 1.9621 | 0.2453 |
| samba | 21,606,400 | 4,440,866 | 1.6443 | 0.2055 |
| sao | 7,251,944 | 4,880,781 | 5.3842 | 0.6730 |
| webster | 41,458,703 | 10,275,355 | 1.9828 | 0.2478 |
| xml | 5,345,280 | 507,417 | 0.7594 | 0.0949 |
| x-ray | 8,474,240 | 4,930,331 | 4.6544 | 0.5818 |
| **TOTAL** | **211,938,580** | **56,731,610** | **2.1414** | **0.2677** |

## Palmist

### About these results

- Configuration: `PalmistConfig(orderCount=4)` - the geometry this provider defaults to, orders 2
  through 5. The geometry travels in the payload, so a caller may pick another one per operation.
- Corpora: the complete [Canterbury Corpus](https://corpus.canterbury.ac.nz/) and
  [Silesia Compression Corpus](https://sun.aei.polsl.pl/~sdeor/index.php?page=silesia).
- Each corpus member is compressed independently through its `CompressionProvider` (the public SPI),
  so every reported size is the complete payload, **including** the SPI framing header (a magic
  number, a family byte, the VLQ-coded original size, then the geometry marker, the format version
  and `orderCount`) and the range coder's final flush.
- Same size/ratio (not CPU) methodology as the LZ77 section: "packed bits per byte" is
  `encodedBytes * 8 / originalBytes`; "packed ratio" is `encodedBytes / originalBytes`.
- Date: 2026-11

The order tables, their confidence gate and the literal coding are described in
[`palmist/README.md`](src/main/kotlin/com/github/penemue/palm/palmist/README.md).

Palmist has no back-references, yet on Canterbury it beats both LZ77 providers - `lz77-lazy` by 2.86%
and `lz77-greedy` by 4.32%. On Silesia it still beats `lz77-greedy`, by 0.22%, but trails `lz77-lazy`
by 1.73%.

The two corpora disagree per file rather than overall. Palmist wins the text members on both -
Canterbury's `plrabn12.txt` (162,199 against the lazy 174,694) and Silesia's `webster` (9,148,094
against 10,275,355) - and loses the ones richest in long repeats, Canterbury's `kennedy.xls` (47,856
against 40,367) and Silesia's `nci` (3,076,536 against 2,280,306).

### Corpus totals

| Corpus | Encoded bytes | Packed bits/byte | Packed ratio |
|---|---:|---:|---:|
| Canterbury Corpus (2,810,784 bytes) | 499,652 | 1.4221 | 0.1778 |
| Silesia Compression Corpus (211,938,580 bytes) | 57,714,742 | 2.1785 | 0.2723 |

### Per-file tables

**Canterbury**

| File | Original bytes | Encoded bytes | Packed bits/byte | Packed ratio |
|---|---:|---:|---:|---:|
| alice29.txt | 152,089 | 48,067 | 2.5284 | 0.3160 |
| asyoulik.txt | 125,179 | 42,779 | 2.7339 | 0.3417 |
| cp.html | 24,603 | 7,915 | 2.5737 | 0.3217 |
| fields.c | 11,150 | 3,361 | 2.4115 | 0.3014 |
| grammar.lsp | 3,721 | 1,343 | 2.8874 | 0.3609 |
| kennedy.xls | 1,029,744 | 47,856 | 0.3718 | 0.0465 |
| lcet10.txt | 426,754 | 121,331 | 2.2745 | 0.2843 |
| plrabn12.txt | 481,861 | 162,199 | 2.6929 | 0.3366 |
| ptt5 | 513,216 | 50,014 | 0.7796 | 0.0975 |
| sum | 38,240 | 12,921 | 2.7031 | 0.3379 |
| xargs.1 | 4,227 | 1,866 | 3.5316 | 0.4414 |
| **TOTAL** | **2,810,784** | **499,652** | **1.4221** | **0.1778** |

**Silesia**

| File | Original bytes | Encoded bytes | Packed bits/byte | Packed ratio |
|---|---:|---:|---:|---:|
| dickens | 10,192,446 | 3,010,835 | 2.3632 | 0.2954 |
| mozilla | 51,220,480 | 17,805,275 | 2.7810 | 0.3476 |
| mr | 9,970,564 | 2,593,489 | 2.0809 | 0.2601 |
| nci | 33,553,445 | 3,076,536 | 0.7335 | 0.0917 |
| ooffice | 6,152,192 | 2,861,561 | 3.7210 | 0.4651 |
| osdb | 10,085,684 | 2,925,306 | 2.3204 | 0.2900 |
| reymont | 6,627,202 | 1,439,256 | 1.7374 | 0.2172 |
| samba | 21,606,400 | 4,740,561 | 1.7552 | 0.2194 |
| sao | 7,251,944 | 5,005,992 | 5.5224 | 0.6903 |
| webster | 41,458,703 | 9,148,094 | 1.7652 | 0.2207 |
| xml | 5,345,280 | 732,487 | 1.0963 | 0.1370 |
| x-ray | 8,474,240 | 4,375,350 | 4.1305 | 0.5163 |
| **TOTAL** | **211,938,580** | **57,714,742** | **2.1785** | **0.2723** |
