# Segmented trace archive transition

## Selected authority

A shard has one manifest published atomically after every durable segment. Segments are append-only framed records with a CRC per frame and a manifest checksum. The manifest lists segment sequence, immutable filename, checksum and committed highest revision. Readers load the last valid manifest then replay frames in sequence into the existing `TraceShardState`; current APIs keep returning that state.

## Publication and recovery

Write segment temp, force it, validate frame CRC, atomically move it, force the directory, then publish/force a replacement manifest. A torn or unlisted segment is ignored. A manifest pointing at a bad segment falls back to the prior manifest backup and records a durable frontier/gap marker. `EvictedShardAuthority` remains authoritative until the manifest revision that contains its snapshot is published; delayed writers must compare revision before publishing.

## First implementation slice

1. Add a segment codec that serializes the existing v3 payload losslessly as a framed snapshot record.
2. Add manifest read/write, checksum validation and replay into `TraceSerializer.read`.
3. Route `TraceStorageManager.writeSnapshot` through manifest publication and retain v3 files as migration source only.
4. Add exact replay, torn-frame, manifest rollback and delayed-eviction ordering tests.

No retention, synthetic scale claim, database, or second archive is introduced. Outage buffering, compaction, spatial/time indexes and legacy root migration remain follow-up work.
