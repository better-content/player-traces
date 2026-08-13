# Traces MVP release capture

Run the focused unit checks first, then the combined GameTest/reobfuscation gate. After static pack validation succeeds, build one matched release and validate those exact distributions:

```sh
tools/bc capture traces \
  --client-zip generated/exports/better-content-playtest-v<N>-curseforge.zip \
  --server-zip generated/exports/better-content-playtest-v<N>-server.zip
```

The command creates one superflat world and keeps a fixed yaw/downward pitch. It captures overlay-off, the player's backward-walk footprints, a created note, the edited note, and the persisted trail/note after a client-only restart with Oculus and Complementary enabled. The summary records both ZIP hashes, their identical embedded Traces JAR hash, and the latest captured/returned/accepted/drawable/submitted counters.

Completion requires manual full-resolution inspection: overlay-off has no Traces geometry; footprints and both note states are plainly visible; the shader-on viewport remains normally lit; and the restart view contains the edited persistent note.
