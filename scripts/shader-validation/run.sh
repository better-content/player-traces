#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PACK_ROOT="${TRACES_REFERENCE_PACK:-/home/dev/better-content}"
DEPS="$ROOT/build/shader-compat/deps"
RUN="$ROOT/build/visual-run"
SHADER_NAME="ComplementaryReimagined_r5.8.1.zip"
SHADER_SOURCE="$PACK_ROOT/shaderpacks/$SHADER_NAME"

mkdir -p "$DEPS"

ensure_artifact() {
  local name="$1"
  local expected_sha1="$2"
  local url="$3"
  local path="$DEPS/$name"
  local actual=""
  if [[ -f "$path" ]]; then
    actual="$(sha1sum "$path" | awk '{print $1}')"
  fi
  if [[ "$actual" != "$expected_sha1" ]]; then
    rm -f "$path"
    curl -fL --retry 3 --output "$path" "$url"
    actual="$(sha1sum "$path" | awk '{print $1}')"
  fi
  [[ "$actual" == "$expected_sha1" ]] || {
    echo "SHA-1 mismatch for $name: expected $expected_sha1, got $actual" >&2
    exit 1
  }
}

ensure_artifact \
  "oculus-mc1.20.1-1.8.0.jar" \
  "984f774e71790deaec674c7587bd24e0711871b2" \
  "https://mediafilez.forgecdn.net/files/6020/952/oculus-mc1.20.1-1.8.0.jar"
ensure_artifact \
  "embeddium-0.3.31+mc1.20.1.jar" \
  "bb2fa8f3e493af16af9160d049f96c614a1faf2f" \
  "https://mediafilez.forgecdn.net/files/5681/725/embeddium-0.3.31%2Bmc1.20.1.jar"

[[ -f "$SHADER_SOURCE" ]] || {
  echo "reference shader pack not found: $SHADER_SOURCE" >&2
  exit 1
}
SHADER_SHA256="$(sha256sum "$SHADER_SOURCE" | awk '{print $1}')"
[[ "$SHADER_SHA256" == "bc0eb8c1ac515f9f83e97fd2b0e05abcf95e49e200d1b04699e68d8c24ee22d7" ]] || {
  echo "reference shader SHA-256 mismatch: $SHADER_SHA256" >&2
  exit 1
}

cd "$ROOT"
./gradlew prepareVisualValidation
mkdir -p "$RUN/shaderpacks" "$RUN/config"
rm -rf "$RUN/mods"
cp "$SHADER_SOURCE" "$RUN/shaderpacks/$SHADER_NAME"
cat >"$RUN/config/oculus.properties" <<EOF
colorSpace=SRGB
disableUpdateMessage=true
enableDebugOptions=false
maxShadowRenderDistance=16
shaderPack=$SHADER_NAME
enableShaders=true
EOF
cat >"$RUN/options.txt" <<'EOF'
version:3465
tutorialStep:none
key_iris.keybind.reload:key.keyboard.unknown
key_iris.keybind.toggleShaders:key.keyboard.unknown
key_iris.keybind.shaderPackSelection:key.keyboard.unknown
EOF
cat >"$RUN/shader-compat-artifacts.json" <<EOF
{
  "minimalRuntimeMods": [
    {"name": "Embeddium", "version": "0.3.31+mc1.20.1", "sha1": "bb2fa8f3e493af16af9160d049f96c614a1faf2f"},
    {"name": "Oculus", "version": "mc1.20.1-1.8.0", "sha1": "984f774e71790deaec674c7587bd24e0711871b2"}
  ],
  "shaderPack": {"name": "$SHADER_NAME", "sha256": "$SHADER_SHA256"},
  "shadersEnabled": true,
  "referencePack": "$PACK_ROOT"
}
EOF

TRACES_REUSE_VISUAL_RUN=1 TRACES_SHADER_COMPAT=1 "$ROOT/scripts/visual-validation/run.sh"
