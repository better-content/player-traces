# Traces MVP release capture

Run the focused unit checks first, then the combined GameTest/reobfuscation gate. Use the repository-owned visual harness for the release capture:

```sh
scripts/visual-validation/run.sh
```

The harness launches an isolated headed Xvfb client and captures deterministic overlay-off, overlay-on, connected-guidance, disconnected-guidance, GUI, and note-echo views. Its generated state remains under the repository's ignored build/runtime directories.

Completion requires manual full-resolution inspection: overlay-off has no Traces geometry, the enabled views show the expected footprints and guidance, GUI rendering remains legible, and note echoes remain visible. The quarantined modpack `tools/` tree is not part of this workflow.
