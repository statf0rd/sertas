#!/usr/bin/env bash
# Собирает портативный Windows-бандл (zip) с вложенной JRE и нативными
# библиотеками под windows-x86_64. Запускать с macOS/Linux — кросс-сборка
# (сервер/клиент на чистой Java; нативные jar'ы качаются под Windows).
#
#   ./scripts/package-windows.sh
#   -> ~/Desktop/sertas-win.zip
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE=/tmp/sertas-win
OUT="${OUT:-$HOME/Desktop/sertas-win.zip}"
JFX=21.0.4
WEBRTC=0.14.0
JACKSON=2.17.2
MC=https://repo1.maven.org/maven2

echo "[1/5] building module jars"
"$ROOT/gradlew" -p "$ROOT" :app-client:jar :media-engine:jar :media:jar :signaling-client:jar :protocol:jar -q

echo "[2/5] staging lib/"
rm -rf "$STAGE"; mkdir -p "$STAGE/lib" "$STAGE/jre"
cp "$ROOT"/{app-client,media-engine,media,signaling-client,protocol}/build/libs/*.jar "$STAGE/lib/"
CACHE="$HOME/.gradle/caches"
for art in "jackson-databind-$JACKSON" "jackson-core-$JACKSON" "jackson-annotations-$JACKSON" "webrtc-java-$WEBRTC"; do
  f=$(find "$CACHE" -name "$art.jar" | grep -vE 'sources|javadoc' | head -1)
  cp "$f" "$STAGE/lib/"
done

echo "[3/5] downloading Windows native jars"
curl -fsSL "$MC/dev/onvoid/webrtc/webrtc-java/$WEBRTC/webrtc-java-$WEBRTC-windows-x86_64.jar" -o "$STAGE/lib/webrtc-java-$WEBRTC-windows-x86_64.jar"
for m in base graphics controls; do
  curl -fsSL "$MC/org/openjfx/javafx-$m/$JFX/javafx-$m-$JFX-win.jar" -o "$STAGE/lib/javafx-$m-$JFX-win.jar"
done

echo "[4/5] downloading Windows JRE (Temurin 21)"
curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse" -o /tmp/jre-win.zip
rm -rf /tmp/jre-win-extract; mkdir -p /tmp/jre-win-extract
unzip -q /tmp/jre-win.zip -d /tmp/jre-win-extract
INNER=$(ls -d /tmp/jre-win-extract/*/ | head -1)
cp -R "$INNER"* "$STAGE/jre/"

echo "[5/5] launcher + zip"
cat > "$STAGE/Запустить sertas.bat" <<'BAT'
@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Запуск sertas... (первый старт может занять 10-20 секунд)
"jre\bin\java.exe" -cp "lib\*" dev.sertas.app.Launcher
echo.
echo ---- Если выше есть ошибка - пришлите скриншот этого окна. ----
pause
BAT
perl -pi -e 's/\r?\n/\r\n/' "$STAGE/Запустить sertas.bat"
rm -f "$OUT"
( cd /tmp && zip -q -r -X "$OUT" sertas-win )
echo "done -> $OUT ($(du -h "$OUT" | cut -f1))"
