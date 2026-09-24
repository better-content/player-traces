# Segmented trace archive transition

## Selected authority

A shard has one manifest published atomically after every durable segment. Each segment is a framed, lossless snapshot encoded by the existing v3 serializer, with a CRC per frame and a manifest checksum. The manifest lists segment sequence, immutable filename, checksum and committed revision. Because each payload contains the complete current shard state, publication retains only the current segment and one prior segment for rollback; older snapshots are compacted after both publication points are durable. Readers validate the selected snapshot and return the existing `TraceShardState` through current APIs.

## Publication and recovery

Write segment temp, force it, validate frame CRC, atomically move it, force the directory, then publish/force a replacement manifest. A torn or unlisted segment is ignored. A manifest pointing at a bad segment falls back to the prior manifest backup and records a durable frontier/gap marker. Only after publication is durable are unreferenced old snapshots deleted, bounding normal retention to two full snapshots per shard. `EvictedShardAuthority` remains authoritative until the manifest revision that contains its snapshot is published; delayed writers must compare revision before publishing.

## First implementation slice

1. Add a segment codec that serializes the existing v3 payload losslessly as a framed snapshot record.
2. Add manifest read/write, checksum validation and snapshot loading through `TraceSerializer.read`.
3. Route `TraceStorageManager.writeSnapshot` through manifest publication and retain v3 files as migration source only.
4. Rebuild per-chunk trace, annotation, and createdAt query indexes from each complete snapshot; update them through mutation/removal paths.
5. Add exact replay, torn-frame, manifest rollback, delayed-eviction ordering, bounded rollover and indexed-query tests.

No retention expiry, synthetic scale claim, database, or permanent second archive is introduced. Bounded snapshot compaction and rebuildable per-chunk trace, annotation, and per-shard `createdAt` indexes are implemented; the time index serves bounded local return-summary queries and is reconstructed when a snapshot loads.

During storage backpressure, eligible FootTrace captures, known-shard seen-state revisions, annotation additions, edits, and removals, exact-position/support block cleanups, bounded multi-shard trace-range cleanup, rain erosion, invalid-support tile pruning, and budget-bounded neighborhood weakening share bounded process-local queues. `tickFlush` rotates first priority among deferred mutation queues each tick. It replays captures idempotently, coalesces seen state monotonically, and orders annotation additions before edits and removals. Seen-state replay waits until queued additions have been applied. Block and range cleanups hide affected traces immediately; tile subscriptions track persisted revision and cleanup count separately and advance wire revisions when cleanup state changes. Rain-exposure operations retain their sequence. Support pruning waits for the target tile to be loaded and never reads an unloaded support chunk; neighborhood weakening preloads all affected shards before mutation.

Queued annotation additions and edits stay query-visible. Already indexed annotations can be edited during backpressure, and queued additions can be edited or canceled. Queued removals and block cleanups are hidden from reads until replay. Every deferred mutation queue shares authority capacity with pending shard snapshots and capture reservations; the queues are not durable across restart. Edits to annotations not already indexed in the current manager, other multi-shard mutations, durable outage reconciliation, legacy-root migration, measured query/write amplification, and candidate/runtime validation remain follow-up work.
