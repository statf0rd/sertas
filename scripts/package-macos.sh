#!/usr/bin/env bash
# Собирает sertas.app для macOS Apple Silicon (arm64) с вложенной JRE и
# нативными библиотеками под macos-aarch64. Адрес сервера/TURN берутся из env
# (не хардкод в репозитории).
#
#   SERTAS_SERVER='ws://...' SERTAS_TURN='turn:...' ./scripts/package-macos.sh
#   -> ~/Desktop/sertas-macos.zip  (внутри sertas.app)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE=/tmp/sertas-macos
OUT="${OUT:-$HOME/Desktop/sertas-macos.zip}"
JFX=21.0.4
WEBRTC=0.14.0
JACKSON=2.17.2
MC=https://repo1.maven.org/maven2
SERVER_URL="${SERTAS_SERVER:-ws://localhost:8080/signal}"
TURN_SPEC="${SERTAS_TURN:-}"

echo "[1/5] building module jars"
"$ROOT/gradlew" -p "$ROOT" :app-client:jar :media-engine:jar :media:jar :signaling-client:jar :protocol:jar -q

APP="$STAGE/sertas.app"
RES="$APP/Contents/Resources"
echo "[2/5] app skeleton + lib/"
rm -rf "$STAGE"; mkdir -p "$APP/Contents/MacOS" "$RES/lib" "$RES/jre"
cp "$ROOT"/{app-client,media-engine,media,signaling-client,protocol}/build/libs/*.jar "$RES/lib/"
CACHE="$HOME/.gradle/caches"
for art in "jackson-databind-$JACKSON" "jackson-core-$JACKSON" "jackson-annotations-$JACKSON" "webrtc-java-$WEBRTC"; do
  f=$(find "$CACHE" -name "$art.jar" | grep -vE 'sources|javadoc' | head -1)
  cp "$f" "$RES/lib/"
done

echo "[2b/5] native audio dylib (ScreenCaptureKit)"
"$ROOT/scripts/build-macos-audio-dylib.sh"
cp "$ROOT/media-engine/build/native/libsertas_audio.dylib" "$RES/lib/"

echo "[3/5] downloading macOS arm64 native jars"
curl -fsSL "$MC/dev/onvoid/webrtc/webrtc-java/$WEBRTC/webrtc-java-$WEBRTC-macos-aarch64.jar" -o "$RES/lib/webrtc-java-$WEBRTC-macos-aarch64.jar"
for m in base graphics controls; do
  curl -fsSL "$MC/org/openjfx/javafx-$m/$JFX/javafx-$m-$JFX-mac-aarch64.jar" -o "$RES/lib/javafx-$m-$JFX-mac-aarch64.jar"
done

echo "[4/5] downloading macOS arm64 JRE (Temurin 21)"
curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jre/hotspot/normal/eclipse" -o /tmp/jre-mac.tar.gz
rm -rf /tmp/jre-mac-extract; mkdir -p /tmp/jre-mac-extract
tar -xzf /tmp/jre-mac.tar.gz -C /tmp/jre-mac-extract
INNER=$(ls -d /tmp/jre-mac-extract/*/ | head -1)
cp -R "$INNER"Contents "$RES/jre/Contents"

echo "[5/5] Info.plist + launcher + zip"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>sertas</string>
  <key>CFBundleDisplayName</key><string>sertas</string>
  <key>CFBundleIdentifier</key><string>dev.sertas.app</string>
  <key>CFBundleVersion</key><string>1.0</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleExecutable</key><string>sertas</string>
  <key>NSMicrophoneUsageDescription</key><string>Голосовая связь в звонке</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
</dict>
</plist>
PLIST

TURN_LINE=""
[ -n "$TURN_SPEC" ] && TURN_LINE="  -Dsertas.turn=\"$TURN_SPEC\" \\"
cat > "$APP/Contents/MacOS/sertas" <<LAUNCH
#!/bin/bash
HERE="\$(cd "\$(dirname "\$0")/../Resources" && pwd)"
exec "\$HERE/jre/Contents/Home/bin/java" \\
  -Dsertas.server="$SERVER_URL" \\
  -Dsertas.audio.dylib="\$HERE/lib/libsertas_audio.dylib" \\
$TURN_LINE
  -cp "\$HERE/lib/*" dev.sertas.app.Launcher
LAUNCH
chmod +x "$APP/Contents/MacOS/sertas"
chmod +x "$RES/jre/Contents/Home/bin/java" 2>/dev/null || true

rm -f "$OUT"
( cd "$STAGE" && zip -q -r -y "$OUT" sertas.app )
echo "done -> $OUT ($(du -h "$OUT" | cut -f1))"
