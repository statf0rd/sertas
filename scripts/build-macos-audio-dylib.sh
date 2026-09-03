#!/usr/bin/env bash
# Собирает нативную библиотеку захвата системного звука (ScreenCaptureKit) в
# media-engine/build/native/libsertas_audio.dylib. macOS 13+.
#
#   ./scripts/build-macos-audio-dylib.sh                 # Apple Silicon (arm64)
#   ARCH=x86_64 ./scripts/build-macos-audio-dylib.sh     # Intel, кросс-сборка
#                                                        # -> build/native/x86_64/
#
# Путь к dylib передаётся приложению через -Dsertas.audio.dylib=<path>.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JH="$(/usr/libexec/java_home)"
ARCH="${ARCH:-arm64}"
OUT="$ROOT/media-engine/build/native"
TARGET_ARG=()
if [ "$ARCH" = "x86_64" ]; then
  OUT="$OUT/x86_64"
  TARGET_ARG=(-target x86_64-apple-macos13.0)
fi
mkdir -p "$OUT"

echo "[1/1] swiftc ($ARCH) -> libsertas_audio.dylib (JDK: $JH)"
swiftc -emit-library -O ${TARGET_ARG[@]+"${TARGET_ARG[@]}"} \
  -import-objc-header "$ROOT/native-capture/macos/jni_bridge.h" \
  -I "$JH/include" -I "$JH/include/darwin" \
  -framework ScreenCaptureKit -framework AVFoundation -framework CoreMedia -framework CoreVideo \
  -o "$OUT/libsertas_audio.dylib" \
  "$ROOT/native-capture/macos/SertasAudio.swift" \
  "$ROOT/native-capture/macos/SertasVideo.swift"

echo "done -> $OUT/libsertas_audio.dylib"
nm -gU "$OUT/libsertas_audio.dylib" | grep -E "nativeStart|nativeRead|nativeStop" || true
