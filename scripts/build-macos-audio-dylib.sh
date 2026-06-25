#!/usr/bin/env bash
# Собирает нативную библиотеку захвата системного звука (ScreenCaptureKit) в
# media-engine/build/native/libsertas_audio.dylib. Apple Silicon (arm64), macOS 13+.
#
#   ./scripts/build-macos-audio-dylib.sh
#
# Путь к dylib передаётся приложению через -Dsertas.audio.dylib=<path>.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JH="$(/usr/libexec/java_home)"
OUT="$ROOT/media-engine/build/native"
mkdir -p "$OUT"

echo "[1/1] swiftc -> libsertas_audio.dylib (JDK: $JH)"
swiftc -emit-library -O \
  -import-objc-header "$ROOT/native-capture/macos/jni_bridge.h" \
  -I "$JH/include" -I "$JH/include/darwin" \
  -framework ScreenCaptureKit -framework AVFoundation -framework CoreMedia \
  -o "$OUT/libsertas_audio.dylib" \
  "$ROOT/native-capture/macos/SertasAudio.swift"

echo "done -> $OUT/libsertas_audio.dylib"
nm -gU "$OUT/libsertas_audio.dylib" | grep -E "nativeStart|nativeRead|nativeStop" || true
