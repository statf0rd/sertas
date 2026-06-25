# Звук демонстрации + по-источниковый микшер у зрителя

**Дата:** 2026-06-26
**Статус:** дизайн одобрен, готов к плану реализации
**Связано:** [Фаза 3 общего дизайна](2026-06-24-discord-analog-voice-app-design.md) — «системный звук демо (ядро ценности)».

## 1. Цель

Две независимые, но связанные подсистемы аудио:

1. **Звук демонстрации.** Тот, кто демонстрирует экран, может тумблером включить
   передачу **системного звука своей машины** (музыка / игра / видео), чтобы
   зрители демки его слышали. Отдельный трек `screen-audio`, стерео-Opus,
   в обход APM (без шумодава/AGC) — чистый звук.
2. **По-источниковый микшер у зрителя.** Каждый слушатель отдельно регулирует
   громкость каждого источника (голос участника / звук его демки), чтобы разные
   звуки не «накладывались» и не заглушали друг друга. Громкость — у **зрителя**,
   а не у источника.

Платформа первого релиза этой фичи: **macOS (Apple Silicon)**. Windows-захват
(WASAPI loopback) — отдельная будущая фаза, вне скоупа.

## 2. Ключевые факты по API (проверено на webrtc-java 0.14.0)

- `CustomAudioSource.pushAudio(byte[] pcm, int bitsPerSample, int sampleRate, int channels, int frameCount)` — есть. Путь подачи PCM для звука демо.
- Удалённый `AudioTrack` **не имеет `setVolume`** — только `setEnabled` (мьют) и
  `getSignalLevel`. Поэтому по-источниковая громкость у зрителя требует
  **собственного микшера**, а не настройки на треке.
- `AudioTrack.addSink(AudioTrackSink)` → `onData(byte[] pcm, int bitsPerSample, int sampleRate, int channels, int frames)` — декодированный PCM **по каждому удалённому треку отдельно**. Основа развязки источников.
- `AudioDeviceModuleBase`: `stopPlayout()/startPlayout()` отдельно от
  `…Recording()` → можно подавить авто-воспроизведение микса, сохранив захват
  микрофона. `setAudioSource(AudioSource)` → `onPlaybackData(...)` — отдать **свой
  микс** обратно в ADM; ADM играет его через реальное устройство, и
  **render-reference для AEC сохраняется** (воспроизведение по-прежнему через ADM).
- `AudioResampler` / `AudioConverter` — готовые помощники согласования форматов.

**Открытый риск (закрывается спайком на железе в Фазе C):** точное поведение
`setAudioSource` + `stopPlayout` — что именно подавляет авто-playout, сохраняет ли
микрофон, и сохраняется ли AEC-reference. Если поведение окажется иным —
запасной путь: собственный вывод через `javax.sound.sampled.SourceDataLine`
(ценой потери авто-AEC-reference; тогда эхо у демонстрирующего лечится тем, что
звук демо в его микрофон не попадает — `excludesCurrentProcessAudio`).

## 3. Архитектура подхода

Расщеплённый дизайн: тестируемый Java-слой зависит только от интерфейсов;
нативный ScreenCaptureKit-хелпер — одна реализация провайдера. Конвертация
Float32→S16 и нарезка в 10мс-кадры — **в Java** (переиспользуем протестированный
`AudioFormatConverter`), натив держим тонким (захват + отдача PCM). **Pull-модель**
JNI: Java-поток тянет `nativeRead(...)`, натив копит SCStream-колбэки в кольцевой
буфер — никаких апколлов Swift→JVM (не нужно аттачить нативные потоки к JVM,
главный источник крашей).

## 4. Компоненты по модулям

### media-engine (Java, headless-тестируемо)
- `SystemAudioProvider` (интерфейс): `void start(PcmSink sink)`, `void stop()`.
  `PcmSink` принимает сырые сэмплы + формат (sampleRate, channels, тип сэмпла).
- `SystemAudioTrack`: владеет `CustomAudioSource` + `AudioTrack("screen-audio")`;
  получает PCM от провайдера, ре-фреймит в 10мс и пушит `pushAudio(...)`.
- `Pcm10msReframer`: чистая логика — кольцевой аккумулятор → ровно 10мс-кадры
  (на 48k стерео = 480 фреймов). Юнит-тесты на нарезку/остаток/смену размера.
- `MacSystemAudioCapture implements SystemAudioProvider`: JNI к `.dylib`,
  pull-модель (`nativeStart/nativeRead/nativeStop`).
- `FakeSystemAudioProvider`: генератор тона/тишины для тестов и headless-прогона
  всего пайплайна без нативного кода.
- `SoftwareMixer` (Фаза C): per-source ring-буферы, gain по источнику, сумма с
  защитой от клиппинга, ресэмпл при необходимости. Чистая логика — юнит-тесты.
- `RemoteAudioSource` (Фаза C): обёртка `(участник × вид)` над `AudioTrackSink`,
  кладёт PCM в ring-буфер микшера.

### media (Java, тестируемо)
- `OpusSdpMunger` → **m-line-aware**: мунжить «музыкальный» профиль (стерео,
  высокий битрейт, без DTX, мимо APM) **только** в секции трека `screen-audio`,
  оставляя `mic` как mono-VOIP. Текущая версия мунжит первый Opus глобально — это
  меняем. Идентификация секции — по `a=msid`/track-id (`screen-audio`); точный
  формат проверить на реальном SDP-дампе при реализации.

### native-capture/macos (Swift, нативно, не headless)
- `.dylib` с `@_cdecl`-JNI-экспортами; `SCStream` + `SCStreamConfiguration`
  `capturesAudio=true`, `excludesCurrentProcessAudio=true` (иначе фидбэк).
  Колбэк аудио → внутренний lock-protected ring-buffer; `nativeRead` дренит.
  Float32 planar 48k стерео отдаём как есть — конверсия в Java.

### app-client (JavaFX)
- Тумблер «Звук демонстрации» (активен только при `isSharing()`).
- Панель-микшер (Фаза C): ползунок + мьют (через gain=0) на каждый источник,
  подписи «Имя — голос» / «Имя — демка».

## 5. Поток данных

**Отправитель (звук демо):**
SCStream (Float32 48k stereo) → нативный ring-buffer → `nativeRead()` →
`Pcm10msReframer` → `AudioFormatConverter.float32PlanarToS16Interleaved` →
`CustomAudioSource.pushAudio` → Opus (stereo-music через SDP-munge, мимо APM) → меш.

**Зритель (микшер):**
каждый удалённый трек → `AudioTrackSink.onData` → `RemoteAudioSource` ring-буфер →
`SoftwareMixer` (gain по источнику, сумма, ресэмпл) → ADM `setAudioSource`/
`onPlaybackData` → реальное устройство вывода (AEC-reference сохранён).

## 6. Согласование треков и SDP

- Трек `screen-audio` добавляется **сразу** при входе в звонок (как `screen`-видео
  сейчас) — m-line согласуется upfront, без renegotiation. До включения тумблера —
  `setEnabled(false)`, капча не запущена.
- Подключить `localSdpTransform` в `MeshCoordinator`/`PeerSession` (сейчас identity).
  Мунжить **только** m-секцию `screen-audio`.
- Жизненный цикл звука демо: тумблер вкл → `setEnabled(true)` + `provider.start`;
  выкл → `provider.stop` + `setEnabled(false)`. Стоп демки → авто-стоп звука.

## 7. Фазы реализации

Порядок **A → B → C**. Фаза C опирается на различение голос/демка, которое вводит
Фаза A; Фаза B даёт реальный звук в этот канал. Каждая фаза проверяема и мёржится
отдельно.

- **Фаза A — звук демо, Java-пайплайн (headless-тестируемо):**
  `SystemAudioProvider`, `SystemAudioTrack`, `Pcm10msReframer`, m-line-aware
  `OpusSdpMunger` + подключение `localSdpTransform`, UI-тумблер,
  `FakeSystemAudioProvider`. Верификация — headless loopback: тон через
  FakeProvider → `CustomAudioSource` → трек → второй пир принимает.
- **Фаза B — нативный захват (не headless):**
  Swift ScreenCaptureKit `.dylib` (`@_cdecl` JNI, pull, ring-buffer),
  Gradle-сборка dylib, упаковка в app-bundle + entitlements
  (`NSAudioCaptureUsageDescription`; Screen Recording TCC уже есть для видео).
  Верификация — ручная на железе.
- **Фаза C — микшер у зрителя + по-источниковая громкость (Java + спайк):**
  `stopPlayout()` + `addSink` на удалённые треки, `SoftwareMixer`,
  `setAudioSource`/`onPlaybackData`, UI-панель микшера, идентификация источника
  по id/msid (фолбэк — `track-meta`). Спайк на железе: подтвердить подавление
  авто-playout, сохранение микрофона и AEC.

## 8. Тестирование

**Headless / CI:**
- `Pcm10msReframer` — нарезка, остаток, смена размера блока.
- `AudioFormatConverter` — уже есть.
- `OpusSdpMunger` m-line-aware — мунжит только `screen-audio`, `mic` нетронут;
  два аудио-m-line; отсутствие `screen-audio` → без изменений.
- Пайплайн через `FakeSystemAudioProvider` — тон проходит CustomAudioSource→трек.
- `SoftwareMixer` — суммирование, gain по источнику, защита от клиппинга,
  underrun→тишина, ресэмпл-фрейминг.

**Ручное на железе:**
- Реальный SCStream-звук, нет фидбэка (`excludesCurrentProcessAudio`).
- A/B качества (музыка через `screen-audio`, стерео-Opus).
- Спайк Фазы C: `setAudioSource`+`stopPlayout` — playout/микрофон/AEC.
- Soak: нет щелчков/underrun на длинном звонке.

## 9. Вне скоупа (YAGNI)

- Громкость на стороне источника (sender-side gain) — выбран зритель.
- Windows WASAPI loopback — отдельная фаза.
- Per-app звук, выбор окна для звука — только «весь системный».
- Core Audio process tap — выбран ScreenCaptureKit.
- Sidecar-процесс — выбран in-process `.dylib`+JNI.
- silence-heartbeat — это про Windows-loopback, не macOS.
- Отдельное onboarding-разрешение под звук — Screen Recording покрывает.

## 10. Риски

| Риск | Митигация |
|------|-----------|
| Краш JVM из нативного кода | Pull-модель: никаких Swift→JVM апколлов; натив только захват+буфер |
| AEC-reference ломается при кастомном playout | Спайк Фазы C; фолбэк — `javax.sound` вывод |
| Real-time щелчки/underrun в микшере | Логика микшера в юнит-тестах; underrun→тишина; ручной soak |
| Неверная m-секция при SDP-munge | Идентификация по msid/track-id; проверка на реальном SDP-дампе; тест на два аудио-m-line |
| Нотаризация / entitlements dylib | Расширяем существующий `scripts/package-macos…`; Screen Recording уже настроен |
