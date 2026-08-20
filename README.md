# Player Traces

Traces is a server-authoritative Forge 1.20.1 mod that records grounded movement and lifecycle markers into compact shard files and renders nearby traces, persistent notes, and animated player echoes.

## What Traces Is
- Captures foot traces from grounded player movement.
- Marks login, logout, respawn, and both sides of dimension travel with anonymous arrival/departure symbols.
- Anchors traces to the exact supporting block and removes them when that support disappears or changes.
- Stores traces by world and region in chunk-sharded binary files.
- Supports globally visible notes that their creator or a server operator can edit. A note may contain text, a colored icon, a gesture echo, or any combination of those components.
- Records the three seconds before the note editor opens, or asks Quark to perform and records a selected emote after Save.
- Keeps player identity out of normal network payloads for rendered traces.

## v1 Scope / Explicit Non-goals
- Scope includes:
  - Grounded movement trace capture.
  - Sharded persistence (v1 custom binary format).
  - Server-side control of trace lifecycle and permissions.
  - A depth-tested client overlay for footprints and notes.
- Non-goals for v1:
  - Team access controls beyond `GLOBAL_TEAM`.
  - Economy/resource extraction, block persistence of annotation sources, or custom pathfinding beyond existing trace graph.
  - Full anti-cheat, moderation UI, or persistent identity exposure in render payloads.

Nearby changed-note guidance is server-authored and shown as bounded floating routes. Erosion and density-based filtering remain deferred.

## Anonymity Model
- Server persists internal player IDs for administration and moderation.
- Client trace packets include no player identity fields.
- Annotation query payloads include only rendering fields (id, optional text/icon, color, position, team, revision, and echo revision), no player identity.
- Bone clips are requested on demand and cached by annotation ID and revision; they contain no player identity or skin data.

## Persistence and Versioning Policy
- Shard path format: `world/data/traces/<dimension-namespace>/<dimension-path>/r.<rx>.<rz>.traces`
  - `<rx>/<rz>` are 16x16 chunk bucket coordinates.
- Binary shard format includes:
  - Header: magic + major/minor + shard bounds.
  - Blocks: foot traces, annotations, seen state, optional version marker.
  - Footer: CRC32 + record count.
- Binary v3 stores trace kind, support identity, and persistent chunk-tile revisions. Loading v2 validates the complete shard, retains annotations and seen state, discards unanchored legacy footprints, and atomically rewrites v3 while retaining the prior file as a backup.
- Unknown future major versions are refused and quarantined.
- Unknown minor versions attempt safe best-effort parsing where compatible.
- Legacy server-root `data/traces` directories are never imported automatically.
- Annotation echoes use separate per-dimension saved data. Each note has at most one 20 Hz, 1–60-frame bone clip; clips are capped at 12 KiB, 2,048 per dimension, and 64 per player. Capacity overflow rejects the mutation instead of evicting an existing gesture.

## Commands
- `/traces debug stats`
- `/traces debug nearby`
- `/traces debug probe`
- `/traces debug storage`
- `/traces debug graph`
- `/traces debug annotation <id>`
- `/traces export json`
- `/traces export csv`

## Test Matrix
- Unit tests target sampling, erosion-safe behavior, annotation CRUD/permissions, seen-state handling, serializer safety, rolling-pose boundaries, clip normalization/trimming/validation, packet round trips, echo persistence/capacity, and playback scheduling/rearming.
- GameTests target persistence/restart, water destruction, rain weakening, annotation persistence, global visibility, ownership, revision enforcement, and guidance invariants.
- Dedicated-server smoke should confirm shard creation, restart-safe persistence, and malformed-shard fallback behavior.
- `scripts/visual-validation/run.sh` launches an isolated headed Xvfb client and records deterministic overlay-off, overlay-on, connected-guidance, disconnected-guidance, GUI, and note-echo screenshots. Every image must be manually inspected before visual completion is claimed.
- Set `tracesModCacheDir` to the directory containing the pinned Quark, Zeta, and Player Animator jars; `- `./gradlew verifyEchoPrototype` exercises` Player Animator with the exact Quark `4.0-462` / Zeta `1.0-31` distribution and writes compression/capture diagnostics to `build/echo-prototype-run/echo-prototype/latest-report.json`.
- Press `G` to toggle Trace Sight. Aim at a block and press `N` to create a note; aim at your existing note and press `N` to save changes or delete it. Gesture choices include the frozen recent three seconds and all Quark emotes available to that client.

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
- Client subscribes to 16x16-block trace tiles and atomically replaces only complete revisions. The default radius is 16 chunks, the client and server maximum is 32, and the per-poll transfer budget does not cap the eventual visible footprint count.
- Annotation mutations are correlated by request ID and acknowledged only after component, permission, reach, revision, capacity, and clip validation. The client retains and reopens the complete draft on failure.

### Note Echo Playback
- Note ghosts are one-shot cyan wireframes shown only through Trace Sight within 18 blocks, with no more than three playing simultaneously.
- Opening sight schedules a deterministic 0.75–2.5 second stagger; completed notes independently cool down for 25–40 seconds, and toggling sight does not reset that cooldown.
- Entering within 1.25 blocks triggers an immediate replay. It rearms only after leaving and waiting ten seconds, and does not change the ordinary cooldown.
- Marker color applies only to the selected icon. Text remains large white lettering with a black border, and gesture-only notes remain discoverable through guidance and proximity playback.

### Rendering Compatibility
- Traces renders only its footprint, guidance, pin, and label geometry into the active world target.
- Footprints and lifecycle markers use the same stable 64x64-cell cache and canonical quad path, with distinct arrival/departure textures. Colors run violet-to-cyan before the viewer's latest login and cyan-to-amber afterward over a configurable 20-minute window.
- Footprints and notes use vanilla depth-tested surface quads at `AFTER_PARTICLES` without changing world colors or compositing the viewport.
- Trace Sight keeps its subtle dim/vignette but draws no cyan border and never suppresses vanilla or modded HUD overlays.
- It does not copy, replace, desaturate, or composite the full world framebuffer.

### Optional revival integration
- When `downed_player_revival` is present, Traces listens to its public downed/revived events without a compile-time dependency.
- Death footage is frozen strictly from frames before the down event and anchored at the down position. Revival discards it; a direct death continues to use the ordinary pre-death buffer.

## Canonical identity

- Repository and release artifact: `player-traces`
- Mod ID and resource namespace: `player_traces`
- Java package: `com.bettercontent.playertraces`
- Validation: `./gradlew verifyFull`

This normalization is a clean break. Worlds, configuration files, and integrations created for earlier identities are not migrated or aliased.
