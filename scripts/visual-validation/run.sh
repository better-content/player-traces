#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$ROOT/build/visual-validation/$RUN_ID"
HELPER="$ROOT/build/visual-helper"

if ! command -v java >/dev/null 2>&1 || ! command -v javac >/dev/null 2>&1; then
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/javac" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  else
    for candidate in "$HOME"/.gradle/jdks/*17* "$HOME"/.gradle/jdks/*17*/jdk-17*; do
      if [[ -x "$candidate/bin/java" && -x "$candidate/bin/javac" ]]; then
        export JAVA_HOME="$candidate"
        export PATH="$JAVA_HOME/bin:$PATH"
        break
      fi
    done
  fi
fi
command -v java >/dev/null 2>&1 && command -v javac >/dev/null 2>&1 || {
  echo "Java 17 JDK not found in PATH, JAVA_HOME, or \$HOME/.gradle/jdks" >&2
  exit 1
}

mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix 2>/dev/null || true

if [[ -n "${TRACES_XVFB_DISPLAY:-}" ]]; then
  DISPLAY_NUM="$TRACES_XVFB_DISPLAY"
else
  DISPLAY_NUM=""
  for candidate in $(seq 90 119); do
    if [[ ! -e "/tmp/.X${candidate}-lock" && ! -e "/tmp/.X11-unix/X${candidate}" ]]; then
      DISPLAY_NUM="$candidate"
      break
    fi
  done
  [[ -n "$DISPLAY_NUM" ]] || { echo "no free Xvfb display in :90-:119" >&2; exit 1; }
fi
DISPLAY_VALUE=":$DISPLAY_NUM"
mkdir -p "$OUT" "$HELPER"

GRADLE_ARGS=()
SHADER_COMPAT=false
SHADER_PACK_JSON=null
if [[ "${TRACES_SHADER_COMPAT:-0}" == "1" ]]; then
  GRADLE_ARGS+=("-PtracesShaderCompat=true")
  SHADER_COMPAT=true
  SHADER_PACK_JSON='"ComplementaryReimagined_r5.8.1.zip"'
fi

client_pid=""
xvfb_pid=""
cleanup() {
  [[ -z "$client_pid" ]] || kill "$client_pid" 2>/dev/null || true
  [[ -z "$xvfb_pid" ]] || kill "$xvfb_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

cd "$ROOT"
if [[ "${TRACES_REUSE_VISUAL_RUN:-0}" == "1" ]]; then
  [[ -f "$ROOT/build/visual-run/saves/Traces Visual/level.dat" ]] || { echo "no persisted visual run to reuse" >&2; exit 1; }
  ./gradlew "${GRADLE_ARGS[@]}" compileKotlin compileJava
else
  rm -rf "$ROOT/build/visual-run"
  ./gradlew "${GRADLE_ARGS[@]}" prepareVisualValidation compileKotlin compileJava
fi
javac -d "$HELPER" scripts/visual-validation/VisualValidationCapture.java

Xvfb "$DISPLAY_VALUE" -screen 0 1280x720x24 -nolisten tcp >"$OUT/xvfb.log" 2>&1 &
xvfb_pid=$!
sleep 1
kill -0 "$xvfb_pid" 2>/dev/null || { echo "Xvfb failed to start on $DISPLAY_VALUE" >&2; exit 1; }
rm -f "$ROOT/build/visual-run/logs/latest.log"
DISPLAY="$DISPLAY_VALUE" ./gradlew "${GRADLE_ARGS[@]}" runClient -PtracesVisual=true >"$OUT/client-console.log" 2>&1 &
client_pid=$!

log="$ROOT/build/visual-run/logs/latest.log"
deadline=$((SECONDS + 180))
if [[ "$SHADER_COMPAT" == "true" ]]; then
  readiness_pattern='TRACES_VISUAL_INTERACTIVE'
else
  readiness_pattern='Traces render-level stage observed'
fi
until [[ -f "$log" ]] && grep -q 'TRACES_VISUAL_FIXTURE' "$log" && grep -q "$readiness_pattern" "$log"; do
  if ! kill -0 "$client_pid" 2>/dev/null; then
    echo "visual client exited before readiness" >&2
    exit 1
  fi
  if grep -Eq 'Unable to initialize GLFW|runClient FAILED' "$OUT/client-console.log" 2>/dev/null; then
    echo "visual client graphics initialization failed" >&2
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    echo "timed out waiting for fixture plus first rendered world stage" >&2
    exit 1
  fi
  sleep 1
done

if [[ "$SHADER_COMPAT" == "true" ]]; then
  grep -Fq 'Using shaderpack: ComplementaryReimagined_r5.8.1.zip' "$log" || {
    echo "Oculus did not activate the expected Complementary shader pack" >&2
    exit 1
  }
  sleep 3
fi

DISPLAY="$DISPLAY_VALUE" java -cp "$HELPER" VisualValidationCapture "$OUT" "$log"
kill "$client_pid" 2>/dev/null || true
wait "$client_pid" || true
client_pid=""

grep -E 'TRACES_VISUAL_(FIXTURE|READY|DISCONNECTED)|Trace query response' "$log" >"$OUT/readiness.log" || true
if grep -Eiq 'renderer fatal|vertex format|NaN|Disabling Traces world desaturation' "$log"; then
  echo "renderer diagnostics contain a fatal visual error" >&2
  exit 1
fi
if [[ "$SHADER_COMPAT" == "true" ]]; then
  cp "$ROOT/build/visual-run/shader-compat-artifacts.json" "$OUT/shader-compat-artifacts.json"
  grep -Fq 'ComplementaryReimagined_r5.8.1.zip' "$OUT/client-console.log" || {
    echo "Complementary shader pack was not observed in the client log" >&2
    exit 1
  }
  if grep -Eiq 'shaderpack failed to load|shader pack.*failed|shader compilation failed|compile failed.*shader|shader.*link failed|could not compile|could not link' "$OUT/client-console.log"; then
    echo "shader compatibility log contains a shader load, compile, or link failure" >&2
    exit 1
  fi
fi
cat >"$OUT/manifest.json" <<EOF
{
  "runId": "$RUN_ID",
  "display": "$DISPLAY_VALUE",
  "resolution": "1280x720",
  "world": "Traces Visual",
  "reusedPersistedWorld": $([[ "${TRACES_REUSE_VISUAL_RUN:-0}" == "1" ]] && echo true || echo false),
  "payloadLimit": 512,
  "desaturation": 0.8,
  "shaderCompatibility": $SHADER_COMPAT,
  "shaderPack": $SHADER_PACK_JSON,
  "screenshots": ["01-overlay-off.png", "02-overlay-on.png", "03-guidance-connected.png", "04-guidance-disconnected.png", "05-depth-occlusion.png", "06-gui-open.png"]
}
EOF
printf 'Visual evidence: %s\n' "$OUT"
