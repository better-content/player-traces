# Changelog

## Unreleased

### Changed

- Replaced full-list footprint responses and mixed dense/sparse rendering with revisioned chunk-tile streaming and a stable canonical cell renderer; the default range is now 16 chunks with a 32-chunk cap.
- Added login-relative violet/cyan/amber trace coloring and distinct login, logout, respawn, and dimension-transition markers.
- Removed Trace Sight's cyan screen border and HUD suppression while retaining its subtle dim/vignette.
- Anchored traces to exact supporting blocks, wired erosion/removal hooks, and kept missing-block annotations visible and editable.
- Upgraded shards to binary v3; v2 migration preserves annotations and seen state while intentionally discarding legacy unanchored footprints.
- Added optional downed-player-revival integration so death echoes freeze the frames preceding the down event.

- Standardized the project as **Player Traces** with mod ID `player_traces`, artifact `player-traces`, and package `com.bettercontent.playertraces`.
- Adopted Java 17 and Forge 1.20.1-47.4.13 as the build baseline without changing the project version.
- This is a clean break; legacy worlds, configurations, and integrations are not migrated.
