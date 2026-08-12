# Traces Visual Completion Loop

## Completion Rule

Traces is not visually complete until every gate below passes with recorded evidence. A compile-only result, packet receipt, or crash-free launch is insufficient.

Run `scripts/visual-validation/run.sh` for an isolated `1280x720` Xvfb capture. It strips persisted Traces data from the copied validation save, constructs a fixed grass/stone/moss scene, fixes the camera server-side, and writes five screenshots plus diagnostics and a manifest under `build/visual-validation/<run-id>/`.
Run `TRACES_REUSE_VISUAL_RUN=1 scripts/visual-validation/run.sh` afterward to validate a real headed stop/start against the persisted accepted scene.

## Required Gates

### 1. Build

- `./gradlew test` passes.
- `./gradlew verifyFast` passes.
- No new compile errors or test failures.

### 2. Clean launch

- Stop all previous Minecraft, Gradle, and Xvfb processes before each run.
- Launch the client in a fresh Xvfb display.
- Enter the test world without startup failure, mod-loading crash, renderer fatal error, or frame abort.

### 3. Data

- The development fixture is seeded at the current player position, never a stale saved location.
- The fixture contains a visible sequence, branch or loop, walk and sprint traces, and an unseen annotation.
- Logs prove the server returned the fixture records and the client accepted them.

### 4. Rendering

- Overlay-disabled screenshot contains no trace visuals.
- Overlay-enabled screenshot visibly contains paired footprints, directional orientation, sprint-vs-walk contrast, and an annotation marker.
- A valid surviving path produces guidance; a disconnected graph produces none.
- No renderer exceptions, vertex-format errors, fatal frame errors, or missing-payload failures occur.
- Marks are terrain-attached, readable, and do not float, flicker, or render below the terrain.
- Solid blocks naturally occlude marks; no trace primitive may use a no-depth render state.

### 5. Quality

- Screenshot comparison shows a measurable increase in trace-colored pixels inside the world viewport when enabled.
- Visuals remain legible over grass, stone, and foliage.
- Dense traces are sampled and exposed rather than rendered as an opaque mass.
- There is no HUD takeover, screen tint, identity exposure, or unrelated input regression.
- World content is desaturated by 80%; traces remain saturated and GUI layers remain normally colored.

### 6. Persistence

- Stop/start simulation preserves fixture traces and annotations.
- Idle operation does not repeatedly rewrite unchanged shards.
- Shutdown produces no repeated flush loop or corruption warning.
- A malformed shard remains diagnosable without preventing the world from loading.

### 7. Regression

- Existing tests remain green after every fix cycle.
- Visual-model tests cover deterministic sampling, sequence-aware orientation, density exposure, render-limit enforcement, and finite alpha values.
- The `G` toggle works and ordinary gameplay input remains usable.

## Fix Loop Protocol

1. Run the complete gate sequence in order.
2. Record the exact failure and fix only the responsible subsystem.
3. Re-run all earlier gates after every code change.
4. Do not accept an unverified screenshot or assume visuals are correct from logs alone.
5. Remove or gate diagnostic logging before completion.
6. Do not declare completion while any gate is failing or unverified.

## Required Evidence

The final report must include:

- Exact commands and pass/fail results.
- Screenshot paths for overlay-off, overlay-on, and guidance states.
- Full-resolution manual inspection of all five captures; pixel statistics and logs are supplementary only.
- Confirmation that screenshots came from a clean client process.
- Log evidence for fixture seed, server response, client receipt, and absence of renderer fatal errors.
- Confirmation that persistence remained dirty-only during idle operation.
- Unrelated environment warnings separated from Traces failures.

## Non-Completion Rule

If the environment prevents a required gate from running, report Traces as **not complete**, identify the blocked gate, and do not present the implementation as finished.
