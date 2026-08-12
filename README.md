# Traces

Traces is a server-authoritative Forge 1.20.1 mod that records grounded player-footstep traces into compact shard files and renders nearby traces/annotations for nearby players.

## What Traces Is
- Captures foot traces from grounded player movement.
- Stores traces by world and region in chunk-sharded binary files.
- Applies environmental erosion rules (water and rain) to weaken/remove traces.
- Supports user annotations (independent spatial records) and on-demand guidance built from surviving traces.
- Keeps player identity out of normal network payloads for rendered traces.

## v1 Scope / Explicit Non-goals
- Scope includes:
  - Grounded movement trace capture.
  - Sharded persistence (v1 custom binary format).
  - Server-side control of trace lifecycle and permissions.
  - Client overlay and guidance based only on surviving trace edges.
- Non-goals for v1:
  - Team access controls beyond `GLOBAL_TEAM`.
  - Economy/resource extraction, block persistence of annotation sources, or custom pathfinding beyond existing trace graph.
  - Full anti-cheat, moderation UI, or persistent identity exposure in render payloads.

## Environmental Rules
- Water-placing/flow events erase traces occupying fluid-contact positions.
- Rain weakens exposed surviving traces over time.
- Traces sheltered from precipitation are preserved longer.
- No global “decay every tick”; erosion is chunk-local and event/interval driven.

## Anonymity Model
- Server persists internal player IDs for administration and moderation.
- Client trace packets include no player identity fields.
- Annotation payloads include only rendering fields (id, text, icon, color, position, team, revision), no player identity.

## Persistence and Versioning Policy
- Shard path format: `world/data/traces/<dimension>/r.<rx>.<rz>.traces`
  - `<rx>/<rz>` are 16x16 chunk bucket coordinates.
- Binary shard format includes:
  - Header: magic + major/minor + shard bounds.
  - Blocks: foot traces, annotations, seen state, optional version marker.
  - Footer: CRC32 + record count.
- Unknown future major versions are refused and quarantined.
- Unknown minor versions attempt safe best-effort parsing where compatible.

## Commands
- `/traces annotation create|update|delete`
- `/traces debug stats`
- `/traces debug nearby`
- `/traces debug probe`
- `/traces debug storage`
- `/traces debug graph`
- `/traces debug annotation <id>`
- `/traces export json`
- `/traces export csv`

## Test Matrix
- Unit tests target sampling, erosion-safe behavior, annotation CRUD/permissions, seen-state handling, and serializer safety.
- GameTests target persistence/restart, water destruction, rain weakening, annotation persistence, and guidance invariants.
- Dedicated-server smoke should confirm shard creation, restart-safe persistence, and malformed-shard fallback behavior.
- `scripts/visual-validation/run.sh` launches an isolated headed Xvfb client and records deterministic overlay-off, overlay-on, connected-guidance, disconnected-guidance, and GUI screenshots. Every image must be manually inspected before visual completion is claimed.

## Architecture Notes
### Domain Boundaries
- Client is read-only for trace payloads: all capture, removal, persistence, permissions, and moderation occur on the server.
- Network packets are transport DTOs for visible traces/annotations only.

### Shard Persistence
- Each shard owns records for one `(dimension, regionX, regionZ)` bucket.
- LRU cache keeps hot shard state in-memory.
- Asynchronous single-threaded flush queue prevents full-world writes and full-table scans.

### Client/Server Trust Boundary
- Server validates all mutations and serves filtered render payloads.
- Client performs local exposure sampling and rendering, and sends only nearby query requests.
