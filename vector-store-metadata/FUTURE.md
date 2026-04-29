# vector-store-metadata — future work

Items here are deliberately deferred from the current implementation.
They are listed so that when a real workload pressures one of them the
context for picking it up is already on file.

## Posting-list sidecar

- **FST-based key index.** Replace the sorted `(key_hash, value_hash)`
  array with a Lucene-style finite-state transducer over the key strings.
  Cheaper to scan, much smaller at high key-cardinality, and naturally
  supports prefix lookups for future range/typed-attribute work. The
  current hash-array layout was picked because it ships in a few hundred
  lines; FSTs are worth the build-time complexity once a workload has
  thousands of distinct keys per segment.

- **Per-key cardinality hint in the header.** Persist the distinct-value
  count for every key (including those skipped at write time). The
  planner can then refuse a posting-list strategy for keys above some
  selectivity threshold without probing the index, and operators can
  surface "would-skip" warnings before the next commit.

- **Block-level compression on the data block.** RoaringBitmaps are
  already container-compressed but the concatenated data block is not.
  Brotli or LZ4 over the data block would help cold-tier storage at the
  cost of decode latency; only worth doing if a workload actually pays
  S3 egress on these sidecars.

- **Per-posting-list skipping lists.** For very large posting lists
  (millions of entries) a skip list would let the AND combinator advance
  past whole containers without materialising both bitmaps. Roaring's
  built-in container layout already does most of this; revisit if
  profiling shows AND time dominating.

## Filter grammar

- **Range operators on numeric attributes** (`$gt`, `$lt`, `$gte`,
  `$lte`, `$between`). Requires the attribute type system below; new
  `FilterExpr.Range` sealed variant; rejected at parse time today.

- **Attribute type system** beyond strings. Numeric, boolean, timestamp.
  Requires a schema carried on the index or negotiated at write time so
  the writer knows how to serialise comparable values into a posting
  layout that supports range scans (one option: per-numeric-key sorted
  ordinal lists alongside the bitmap).

- **Cost-based strategy selection.** Today the planner picks the
  posting-list strategy whenever every leaf resolves and falls back to
  scan otherwise. A cost-based variant would also consider the per-key
  cardinality hint above and the bitmap selectivity to pick the cheaper
  path.

## Sidecar cache

- **Multi-tier sidecar cache.** An `L2Provider` behind the heap tier
  (the same shape the block cache already uses) when sidecar working
  sets routinely exceed the heap budget. The byte-weighted L1 plus an
  off-heap or NVMe L2 mirror would avoid sidecar parsing churn at the
  cost of one extra serialisation hop on miss.
