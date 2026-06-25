# Phase B — macOS ScreenCaptureKit System-Audio Capture (native) Implementation Plan

> **For agentic workers:** continues the screen-share audio feature. Phase A (Java pipeline) is done; this replaces the placeholder `FakeSystemAudioProvider(440)` with real system-audio capture.

**Goal:** Захватывать системный звук машины демонстрирующего через ScreenCaptureKit и отдавать его в `SystemAudioTrack` — зрители слышат реальный звук демо, а не тон.

**Architecture:** Swift `.dylib` (`@_cdecl` JNI, pull-модель: ring-buffer + `nativeStart/nativeRead/nativeStop`, без апколлов Swift→JVM). `MacSystemAudioCapture implements SystemAudioProvider` — Java-поток тянет планарный Float32 из натива и отдаёт в `PcmSink`. Загрузка dylib по системному свойству `sertas.audio.dylib`.

**Tech Stack:** Swift 6.1 · ScreenCaptureKit (`SCStream`, `capturesAudio`, `excludesCurrentProcessAudio`) · JNI (jni.h из JDK 21) · webrtc-java `CustomAudioSource`.

**Спека:** [2026-06-26-screen-share-audio-and-mixer-design.md](../specs/2026-06-26-screen-share-audio-and-mixer-design.md) · **Фаза A:** [2026-06-26-phase-a-screen-share-audio-pipeline.md](2026-06-26-phase-a-screen-share-audio-pipeline.md)

## Проверено спайком (де-риск выполнен)

- `swiftc -emit-library` собирает dylib; `@_cdecl("Java_dev_sertas_engine_MacSystemAudioCapture_nativePing")` экспортирует JNI-символ; `System.load(path)` грузит его → `nativePing()` вернул 42.
- Маршалинг `float[]`: Swift + `jni.h` (`-import-objc-header`, `-I $JH/include -I $JH/include/darwin`) → `env.pointee?.pointee.SetFloatArrayRegion` заполнил Java-массив. Работает в Swift 6.1.
- Окружение: macOS 26.5.1, ScreenCaptureKit.framework в SDK, `jni.h` в `ms-21.0.8`.

## File Structure

**Создаются/дорабатываются:**
- `native-capture/macos/SertasAudio.swift` — ring-buffer + `nativeStart/nativeRead/nativeStop` + ScreenCaptureKit capture (сейчас содержит спайк ping/fill — заменяется).
- `native-capture/macos/jni_bridge.h` — `#include <jni.h>` (есть).
- `scripts/build-macos-audio-dylib.sh` — сборка dylib (swiftc + фреймворки).
- `media-engine/src/main/java/dev/sertas/engine/MacSystemAudioCapture.java` — провайдер + read-loop (сейчас скелет ping/fill — заменяется).
- `app-client/.../CallController.java` — выбрать `MacSystemAudioCapture` если доступен, иначе fallback.
- `scripts/package-macos.sh` — вложить dylib в бандл + лаунчер ставит `-Dsertas.audio.dylib`.

## Задачи

### Task 1: Build-скрипт dylib
`scripts/build-macos-audio-dylib.sh`: `swiftc -emit-library -O -import-objc-header native-capture/macos/jni_bridge.h -I "$JH/include" -I "$JH/include/darwin" -framework ScreenCaptureKit -framework AVFoundation -framework CoreMedia -o media-engine/build/native/libsertas_audio.dylib native-capture/macos/SertasAudio.swift`, где `JH=$(/usr/libexec/java_home)`. Проверка: запуск собирает dylib, `nm -gU` показывает JNI-символы.

### Task 2: Нативный ring-buffer + pull API (с тест-тоном)
В `SertasAudio.swift`: глобальный потокобезопасный ring-buffer планарных L/R; `nativeStart` запускает поток, наполняющий ring **нативным синусом** (временно, для проверки pull-пути без SCStream); `nativeRead(left[], right[], maxFrames) -> framesRead` дренит через `SetFloatArrayRegion`; `nativeStop`. Java-смоук: start → read в цикле → видим ненулевые сэмплы → stop. **Доказывает pull-маршалинг до подключения SCStream.**

### Task 3: ScreenCaptureKit-захват вместо тона
Заменить генератор тона на `SCStream`: `SCShareableContent.getWithCompletionHandler` → первый `display` → `SCContentFilter(display:excludingWindows:[])` → `SCStreamConfiguration(capturesAudio=true, excludesCurrentProcessAudio=true, sampleRate=48000, channelCount=2, width=2, height=2)` → `addStreamOutput(self, type:.audio)` → `startCapture`. В `stream(_:didOutputSampleBuffer:of:)` для `.audio`: `CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer` → `UnsafeMutableAudioBufferListPointer` → планарный (2 буфера) или interleaved (1 буфер, деинтерлив) Float32 → в ring. Проверка: компилируется и линкуется; **реальный звук — ручная проверка на железе** (нужно разрешение Screen Recording).

### Task 4: Java-провайдер `MacSystemAudioCapture`
Заменить ping/fill на: `implements SystemAudioProvider`; `start(PcmSink)` → `nativeStart()` + поток, который циклом `nativeRead(buf, buf, 480)` отдаёт `sink.onPcm(left, right, 48000)` (re-use буферов; спать ~5мс при 0 фреймов); `stop()` → остановить поток + `nativeStop()`. `isAvailable()` уже есть.

### Task 5: Wire в `CallController`
В `startScreenAudio()`: `SystemAudioProvider p = MacSystemAudioCapture.isAvailable() ? new MacSystemAudioCapture() : null;` если null → `onError("нативный захват звука недоступен")` и не включать (тумблер откатить в UI). Убрать `FakeSystemAudioProvider(440)` из прод-пути (оставить класс для тестов).

### Task 6: Сборка/запуск + упаковка
- Dev-run: `./gradlew :app-client:run` с `-Dsertas.audio.dylib=…/libsertas_audio.dylib` (через `application { applicationDefaultJvmArgs }` или env). Документировать в DEPLOY.
- `package-macos.sh`: собрать dylib, скопировать в `Resources/lib/`, лаунчер добавляет `-Dsertas.audio.dylib="$HERE/lib/libsertas_audio.dylib"`. Info.plist: Screen Recording — TCC рантайма, отдельный ключ не нужен; добавить комментарий.

### Task 7: Ручная верификация на железе
Два клиента; демонстрирующий включает «Звук демонстрации»; система просит Screen Recording (один раз); зритель слышит реальный звук (музыка/видео) с машины демонстрирующего; нет фидбэка (`excludesCurrentProcessAudio`); голос микрофона по-прежнему отдельным треком и чистый.

## Риски
| Риск | Митигация |
|------|-----------|
| Формат аудио SCStream (planar vs interleaved) | Обрабатывать оба (mNumberBuffers 2 → planar, 1 → деинтерлив) |
| Краш JVM из натива | Pull-модель; натив только пишет в ring, JVM-потоков не аттачит |
| dylib не собран на чужой машине | `isAvailable()`==false → graceful, без краша |
| Screen Recording не выдан | SCStream вернёт ошибку старта → `onError`, тумблер откат |
