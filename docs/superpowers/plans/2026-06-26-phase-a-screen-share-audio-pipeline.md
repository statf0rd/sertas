# Phase A — Screen-Share Audio Pipeline (Java) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Передать системный звук демонстрации зрителям отдельным треком `screen-audio` (стерео-Opus, мимо APM), с полностью тестируемым Java-пайплайном; реальный нативный захват — Фаза B.

**Architecture:** Тонкий провайдер-интерфейс `SystemAudioProvider` отдаёт планарный Float32; `SystemAudioTrack` ре-фреймит в 10мс, конвертирует в S16 (переиспользуя `AudioFormatConverter`) и пушит в `CustomAudioSource`. m-line-aware `OpusSdpMunger` мунжит «музыкальный» профиль только в секции `screen-audio`. В Фазе A источник — `FakeSystemAudioProvider` (синус), его заменит `MacSystemAudioCapture` в Фазе B.

**Tech Stack:** JDK 21 · webrtc-java 0.14.0 (`CustomAudioSource.pushAudio`, `AudioTrack`) · JavaFX 21 · JUnit 5 · Gradle.

**Спека:** [docs/superpowers/specs/2026-06-26-screen-share-audio-and-mixer-design.md](../specs/2026-06-26-screen-share-audio-and-mixer-design.md)

---

## File Structure

**Создаются:**
- `media-engine/src/main/java/dev/sertas/engine/Pcm10msReframer.java` — накопитель планарного Float32 в ровные 10мс-блоки (чистая логика).
- `media-engine/src/main/java/dev/sertas/engine/SystemAudioProvider.java` — интерфейс источника + `PcmSink`.
- `media-engine/src/main/java/dev/sertas/engine/FakeSystemAudioProvider.java` — синус-источник на фоновом потоке (тесты + временный источник Фазы A).
- `media-engine/src/main/java/dev/sertas/engine/SystemAudioTrack.java` — `CustomAudioSource` + трек `screen-audio`, реализует `PcmSink`.
- `media-engine/src/test/java/dev/sertas/engine/Pcm10msReframerTest.java`
- `media-engine/src/test/java/dev/sertas/engine/FakeSystemAudioProviderTest.java`
- `media-engine/src/test/java/dev/sertas/engine/SystemAudioTrackTest.java`
- `media-engine/src/test/java/dev/sertas/engine/ScreenAudioTrackOfferTest.java`
- `media-engine/src/test/java/dev/sertas/engine/ScreenAudioLoopbackTest.java`
- `media/src/test/java/dev/sertas/media/OpusSdpMungerTrackTest.java`

**Модифицируются:**
- `media/src/main/java/dev/sertas/media/OpusSdpMunger.java` — добавить m-line-aware метод `applyMusicProfileToTrack`.
- `media-engine/src/main/java/dev/sertas/engine/WebRtcEngine.java` — добавить `createAudioTrack(label, source)`.
- `media-engine/src/main/java/dev/sertas/engine/MeshCoordinator.java` — подключить SDP-трансформ для секции `screen-audio`.
- `app-client/src/main/java/dev/sertas/app/CallController.java` — создать `screen-audio` трек, методы start/stop звука демо.
- `app-client/src/main/java/dev/sertas/app/ui/CallView.java` — кнопка «Звук демонстрации».
- `app-client/src/main/java/dev/sertas/app/SertasApp.java` — связать кнопку с контроллером.

---

## Task 1: `Pcm10msReframer` — нарезка PCM в 10мс-блоки

**Files:**
- Create: `media-engine/src/main/java/dev/sertas/engine/Pcm10msReframer.java`
- Test: `media-engine/src/test/java/dev/sertas/engine/Pcm10msReframerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.sertas.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Pcm10msReframerTest {

    @Test
    void blockSizeIs480At48k() {
        assertEquals(480, new Pcm10msReframer(48_000).framesPerBlock());
    }

    @Test
    void emitsFullBlocksAndKeepsRemainder() {
        Pcm10msReframer r = new Pcm10msReframer(48_000);
        List<Pcm10msReframer.Block> first = r.offer(ramp(700), ramp(700));
        assertEquals(1, first.size());
        assertEquals(480, first.get(0).left().length);
        assertEquals(480, first.get(0).right().length);

        // 220 в остатке + 300 = 520 → ещё один блок, 40 в остатке
        List<Pcm10msReframer.Block> second = r.offer(ramp(300), ramp(300));
        assertEquals(1, second.size());
        assertEquals(480, second.get(0).left().length);
    }

    @Test
    void exactBlockEmitsOneAndNoRemainder() {
        Pcm10msReframer r = new Pcm10msReframer(48_000);
        assertEquals(1, r.offer(new float[480], new float[480]).size());
        assertEquals(0, r.offer(new float[0], new float[0]).size());
    }

    @Test
    void partialChunkEmitsNothing() {
        Pcm10msReframer r = new Pcm10msReframer(48_000);
        assertTrue(r.offer(new float[100], new float[100]).isEmpty());
    }

    @Test
    void preservesSampleOrderAcrossBlocks() {
        Pcm10msReframer r = new Pcm10msReframer(48_000);
        List<Pcm10msReframer.Block> blocks = r.offer(ramp(960), ramp(960));
        assertEquals(2, blocks.size());
        assertEquals(0f, blocks.get(0).left()[0]);
        assertEquals(479f, blocks.get(0).left()[479]);
        assertEquals(480f, blocks.get(1).left()[0]);
        assertEquals(959f, blocks.get(1).left()[479]);
    }

    @Test
    void rejectsMismatchedChannelLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> new Pcm10msReframer(48_000).offer(new float[10], new float[11]));
    }

    private static float[] ramp(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :media-engine:test --tests 'dev.sertas.engine.Pcm10msReframerTest'`
Expected: FAIL — `Pcm10msReframer` не существует (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package dev.sertas.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Накопитель PCM в ровные 10мс-кадры. Источник системного звука отдаёт чанки
 * произвольной длины; {@code CustomAudioSource} ждёт стабильный 10мс-ритм
 * (на 48кГц — 480 фреймов). Класс копит планарные Float32-сэмплы (L/R) и выдаёт
 * полные блоки, храня остаток до следующего вызова. Только логика, без нативного кода.
 */
public final class Pcm10msReframer {

    /** Готовый 10мс-блок: планарные L/R одинаковой длины {@link #framesPerBlock()}. */
    public record Block(float[] left, float[] right) {}

    private final int framesPerBlock;
    private float[] left = new float[0];
    private float[] right = new float[0];
    private int size; // валидных фреймов накоплено

    /** @param sampleRate частота дискретизации (48000 → блок 480 фреймов). */
    public Pcm10msReframer(int sampleRate) {
        this.framesPerBlock = sampleRate / 100;
    }

    public int framesPerBlock() {
        return framesPerBlock;
    }

    /** Добавить планарный чанк (L и R одной длины); вернуть готовые 10мс-блоки. */
    public List<Block> offer(float[] chunkLeft, float[] chunkRight) {
        if (chunkLeft.length != chunkRight.length) {
            throw new IllegalArgumentException(
                    "channel length mismatch: " + chunkLeft.length + " vs " + chunkRight.length);
        }
        ensureCapacity(size + chunkLeft.length);
        System.arraycopy(chunkLeft, 0, left, size, chunkLeft.length);
        System.arraycopy(chunkRight, 0, right, size, chunkRight.length);
        size += chunkLeft.length;

        List<Block> blocks = new ArrayList<>();
        int consumed = 0;
        while (size - consumed >= framesPerBlock) {
            float[] bl = new float[framesPerBlock];
            float[] br = new float[framesPerBlock];
            System.arraycopy(left, consumed, bl, 0, framesPerBlock);
            System.arraycopy(right, consumed, br, 0, framesPerBlock);
            blocks.add(new Block(bl, br));
            consumed += framesPerBlock;
        }
        if (consumed > 0) {
            int remain = size - consumed;
            System.arraycopy(left, consumed, left, 0, remain);
            System.arraycopy(right, consumed, right, 0, remain);
            size = remain;
        }
        return blocks;
    }

    private void ensureCapacity(int n) {
        if (left.length >= n) {
            return;
        }
        int cap = Math.max(n, framesPerBlock * 4);
        float[] nl = new float[cap];
        float[] nr = new float[cap];
        System.arraycopy(left, 0, nl, 0, size);
        System.arraycopy(right, 0, nr, 0, size);
        left = nl;
        right = nr;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :media-engine:test --tests 'dev.sertas.engine.Pcm10msReframerTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add media-engine/src/main/java/dev/sertas/engine/Pcm10msReframer.java \
        media-engine/src/test/java/dev/sertas/engine/Pcm10msReframerTest.java
git commit -m "feat(engine): Pcm10msReframer — нарезка планарного PCM в 10мс-блоки"
```

---

## Task 2: `OpusSdpMunger.applyMusicProfileToTrack` — мунж только секции трека

**Files:**
- Modify: `media/src/main/java/dev/sertas/media/OpusSdpMunger.java`
- Test: `media/src/test/java/dev/sertas/media/OpusSdpMungerTrackTest.java`

Существующий `applyMusicProfile(sdp)` мунжит первый Opus во всём SDP. Для двух аудио-дорожек (микрофон mono-VOIP + звук-демки stereo-music) нужно мунжить **только** секцию `screen-audio`. Добавляем новый метод, рефакторим общий код в `mungeOpusFmtp`, старый метод и его тесты не трогаем.

- [ ] **Step 1: Write the failing test**

```java
package dev.sertas.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpusSdpMungerTrackTest {

    private static final String TWO_AUDIO = String.join("\r\n",
            "v=0",
            "o=- 0 0 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=mid:0",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "a=msid:sertas mic",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=mid:1",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "a=msid:sertas screen-audio",
            "");

    @Test
    void mungesOnlyScreenAudioSection() {
        String out = OpusSdpMunger.applyMusicProfileToTrack(TWO_AUDIO, "screen-audio");
        // Ровно одна секция получила музыкальный профиль.
        assertEquals(1, count(out, "stereo=1"), out);

        String micSection = sectionContaining(out, "msid:sertas mic");
        String screenSection = sectionContaining(out, "msid:sertas screen-audio");
        assertFalse(micSection.contains("stereo=1"), "микрофон не должен мунжиться:\n" + micSection);
        assertTrue(screenSection.contains("stereo=1"), "звук демо должен быть stereo:\n" + screenSection);
        assertTrue(screenSection.contains("usedtx=0"), screenSection);
    }

    @Test
    void unchangedWhenMarkerAbsent() {
        assertEquals(TWO_AUDIO, OpusSdpMunger.applyMusicProfileToTrack(TWO_AUDIO, "no-such-track"));
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    /** Кусок SDP от m= секции, содержащей marker, до следующего m= (или конца). */
    private static String sectionContaining(String sdp, String marker) {
        int mark = sdp.indexOf(marker);
        int start = sdp.lastIndexOf("m=", mark);
        int next = sdp.indexOf("\r\nm=", mark);
        int end = next < 0 ? sdp.length() : next;
        return sdp.substring(start, end);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :media:test --tests 'dev.sertas.media.OpusSdpMungerTrackTest'`
Expected: FAIL — метод `applyMusicProfileToTrack` не существует.

- [ ] **Step 3: Add the new method (refactor shared body into `mungeOpusFmtp`)**

В `OpusSdpMunger.java` замените метод `applyMusicProfile` на версию, делегирующую в новый приватный `mungeOpusFmtp`, и добавьте `applyMusicProfileToTrack` + `splitSections`. Импорты (`java.util.regex.*`, `LinkedHashMap`, `Map`) уже есть; добавьте `java.util.ArrayList` и `java.util.List`.

Замените существующий метод:

```java
    public static String applyMusicProfile(String sdp) {
        Matcher rtpmap = RTPMAP.matcher(sdp);
        if (!rtpmap.find()) {
            return sdp;
        }
        String pt = rtpmap.group(1);
        String eol = sdp.contains("\r\n") ? "\r\n" : "\n";

        Pattern fmtp = Pattern.compile("(?m)^a=fmtp:" + pt + " (.*)$");
        Matcher fm = fmtp.matcher(sdp);
        if (fm.find()) {
            Map<String, String> params = parse(fm.group(1));
            params.putAll(MUSIC);
            return new StringBuilder(sdp)
                    .replace(fm.start(), fm.end(), "a=fmtp:" + pt + " " + render(params))
                    .toString();
        }

        // fmtp-строки нет — вставляем сразу после rtpmap-строки
        int insert = sdp.indexOf(eol, rtpmap.end());
        String line = eol + "a=fmtp:" + pt + " " + render(new LinkedHashMap<>(MUSIC));
        return insert < 0
                ? sdp + line
                : sdp.substring(0, insert) + line + sdp.substring(insert);
    }
```

на:

```java
    /** Мунж первого Opus во всём SDP (обратная совместимость). */
    public static String applyMusicProfile(String sdp) {
        return mungeOpusFmtp(sdp);
    }

    /**
     * Применяет «музыкальный» профиль Opus ТОЛЬКО к m-секции, содержащей маркер
     * (id трека из {@code a=msid}, напр. {@code "screen-audio"}). Прочие аудио-секции
     * (микрофон — mono VOIP) не трогает. Маркер не найден → SDP без изменений.
     */
    public static String applyMusicProfileToTrack(String sdp, String trackMarker) {
        String[] parts = splitSections(sdp);
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String section = parts[i];
            if (section.startsWith("m=audio") && section.contains(trackMarker)) {
                out.append(mungeOpusFmtp(section));
            } else {
                out.append(section);
            }
        }
        return out.toString();
    }

    /** parts[0] — session-заголовок (до первой m=); parts[i>=1] — m-секции с разделителями. */
    private static String[] splitSections(String sdp) {
        List<Integer> starts = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^m=").matcher(sdp);
        while (m.find()) {
            starts.add(m.start());
        }
        if (starts.isEmpty()) {
            return new String[]{sdp};
        }
        List<String> parts = new ArrayList<>();
        parts.add(sdp.substring(0, starts.get(0)));
        for (int i = 0; i < starts.size(); i++) {
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : sdp.length();
            parts.add(sdp.substring(starts.get(i), end));
        }
        return parts.toArray(new String[0]);
    }

    /** Мунж первого Opus-fmtp в переданном фрагменте SDP (секции или целого SDP). */
    private static String mungeOpusFmtp(String sdp) {
        Matcher rtpmap = RTPMAP.matcher(sdp);
        if (!rtpmap.find()) {
            return sdp;
        }
        String pt = rtpmap.group(1);
        String eol = sdp.contains("\r\n") ? "\r\n" : "\n";

        Pattern fmtp = Pattern.compile("(?m)^a=fmtp:" + pt + " (.*)$");
        Matcher fm = fmtp.matcher(sdp);
        if (fm.find()) {
            Map<String, String> params = parse(fm.group(1));
            params.putAll(MUSIC);
            return new StringBuilder(sdp)
                    .replace(fm.start(), fm.end(), "a=fmtp:" + pt + " " + render(params))
                    .toString();
        }

        int insert = sdp.indexOf(eol, rtpmap.end());
        String line = eol + "a=fmtp:" + pt + " " + render(new LinkedHashMap<>(MUSIC));
        return insert < 0
                ? sdp + line
                : sdp.substring(0, insert) + line + sdp.substring(insert);
    }
```

Добавьте импорты в начало файла рядом с существующими:

```java
import java.util.ArrayList;
import java.util.List;
```

- [ ] **Step 4: Run tests to verify both old and new pass**

Run: `./gradlew :media:test`
Expected: PASS — `OpusSdpMungerTest` (4, без изменений) и `OpusSdpMungerTrackTest` (2).

- [ ] **Step 5: Commit**

```bash
git add media/src/main/java/dev/sertas/media/OpusSdpMunger.java \
        media/src/test/java/dev/sertas/media/OpusSdpMungerTrackTest.java
git commit -m "feat(media): m-line-aware munging — музыкальный профиль только для секции трека"
```

---

## Task 3: `SystemAudioProvider` + `FakeSystemAudioProvider`

**Files:**
- Create: `media-engine/src/main/java/dev/sertas/engine/SystemAudioProvider.java`
- Create: `media-engine/src/main/java/dev/sertas/engine/FakeSystemAudioProvider.java`
- Test: `media-engine/src/test/java/dev/sertas/engine/FakeSystemAudioProviderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.sertas.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FakeSystemAudioProviderTest {

    @Test
    void emitsPlanarStereoChunksUntilStopped() throws Exception {
        FakeSystemAudioProvider p = new FakeSystemAudioProvider(440);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean badShape = new AtomicBoolean();
        AtomicBoolean sawNonZero = new AtomicBoolean();

        p.start((left, right, sampleRate) -> {
            if (left.length != 480 || right.length != 480 || sampleRate != 48_000) {
                badShape.set(true);
            }
            for (float v : left) {
                if (v != 0f) {
                    sawNonZero.set(true);
                }
            }
            calls.incrementAndGet();
        });

        Thread.sleep(150);
        p.stop();
        int afterStop = calls.get();
        Thread.sleep(80);

        assertTrue(afterStop >= 3, "ожидали ≥3 чанка за 150мс, получили " + afterStop);
        assertFalse(badShape.get(), "неверная форма чанка");
        assertTrue(sawNonZero.get(), "синус 440Гц не должен быть тишиной");
        assertEquals(afterStop, calls.get(), "после stop() чанки не должны приходить");
    }

    @Test
    void zeroFrequencyIsSilence() throws Exception {
        FakeSystemAudioProvider p = new FakeSystemAudioProvider(0);
        AtomicReference<Boolean> nonZero = new AtomicReference<>(false);
        p.start((left, right, sr) -> {
            for (float v : left) {
                if (v != 0f) {
                    nonZero.set(true);
                }
            }
        });
        Thread.sleep(60);
        p.stop();
        assertFalse(nonZero.get(), "частота 0 → тишина");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :media-engine:test --tests 'dev.sertas.engine.FakeSystemAudioProviderTest'`
Expected: FAIL — классы не существуют (compilation error).

- [ ] **Step 3: Write the interface**

`SystemAudioProvider.java`:

```java
package dev.sertas.engine;

/**
 * Источник системного звука демонстрации. Отдаёт планарный стерео Float32 в
 * {@link PcmSink}. Реализации: нативный ScreenCaptureKit (Фаза B) или
 * {@link FakeSystemAudioProvider} (тесты и временный источник Фазы A).
 */
public interface SystemAudioProvider {

    /** Начать захват, отдавая планарный стерео Float32 в {@code sink}. */
    void start(PcmSink sink);

    /** Остановить захват. После вызова {@code sink} больше не вызывается. */
    void stop();

    /** Приёмник планарного стерео Float32 PCM (L и R одной длины). */
    interface PcmSink {
        void onPcm(float[] left, float[] right, int sampleRate);
    }
}
```

- [ ] **Step 4: Write `FakeSystemAudioProvider`**

```java
package dev.sertas.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фейковый источник системного звука: синус заданной частоты, планарный стерео
 * 48кГц, чанки по 10мс (480 фреймов) на фоновом потоке. Для headless-тестов
 * пайплайна и как временный источник Фазы A (до нативного ScreenCaptureKit в
 * Фазе B). Частота {@code <= 0} → тишина.
 */
public final class FakeSystemAudioProvider implements SystemAudioProvider {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAMES_PER_CHUNK = SAMPLE_RATE / 100; // 10мс = 480

    private final double frequencyHz;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread thread;
    private long phase;

    public FakeSystemAudioProvider(double frequencyHz) {
        this.frequencyHz = frequencyHz;
    }

    @Override
    public void start(PcmSink sink) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> run(sink), "fake-system-audio");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        thread = null;
    }

    private void run(PcmSink sink) {
        while (running.get()) {
            float[] left = new float[FRAMES_PER_CHUNK];
            float[] right = new float[FRAMES_PER_CHUNK];
            for (int i = 0; i < FRAMES_PER_CHUNK; i++) {
                float s = frequencyHz <= 0 ? 0f
                        : (float) (0.25 * Math.sin(2 * Math.PI * frequencyHz * (phase++) / SAMPLE_RATE));
                left[i] = s;
                right[i] = s;
            }
            sink.onPcm(left, right, SAMPLE_RATE);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :media-engine:test --tests 'dev.sertas.engine.FakeSystemAudioProviderTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add media-engine/src/main/java/dev/sertas/engine/SystemAudioProvider.java \
        media-engine/src/main/java/dev/sertas/engine/FakeSystemAudioProvider.java \
        media-engine/src/test/java/dev/sertas/engine/FakeSystemAudioProviderTest.java
git commit -m "feat(engine): SystemAudioProvider + FakeSystemAudioProvider (синус-источник)"
```

---

## Task 4: `SystemAudioTrack` + `WebRtcEngine.createAudioTrack`

**Files:**
- Modify: `media-engine/src/main/java/dev/sertas/engine/WebRtcEngine.java`
- Create: `media-engine/src/main/java/dev/sertas/engine/SystemAudioTrack.java`
- Test: `media-engine/src/test/java/dev/sertas/engine/SystemAudioTrackTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.sertas.engine;

import dev.onvoid.webrtc.media.MediaStreamTrack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemAudioTrackTest {

    @Test
    void createsDisabledAudioTrackLabeledScreenAudio() {
        WebRtcEngine engine = WebRtcEngine.headless();
        try {
            SystemAudioTrack sat = new SystemAudioTrack(engine);
            assertEquals(MediaStreamTrack.AUDIO_TRACK_KIND, sat.track().getKind());
            assertFalse(sat.track().isEnabled(), "до start трек выключен");
        } finally {
            engine.dispose();
        }
    }

    @Test
    void pushingExactBlockDoesNotThrow() {
        WebRtcEngine engine = WebRtcEngine.headless();
        try {
            SystemAudioTrack sat = new SystemAudioTrack(engine);
            // Ровно 480 фреймов → один pushAudio в нативный CustomAudioSource.
            assertDoesNotThrow(() -> sat.onPcm(new float[480], new float[480], 48_000));
        } finally {
            engine.dispose();
        }
    }

    @Test
    void startEnablesAndStopDisablesTrack() {
        WebRtcEngine engine = WebRtcEngine.headless();
        try {
            SystemAudioTrack sat = new SystemAudioTrack(engine);
            sat.start(new FakeSystemAudioProvider(0)); // тишина, чтобы не шуметь
            assertTrue(sat.track().isEnabled());
            sat.stop();
            assertFalse(sat.track().isEnabled());
        } finally {
            engine.dispose();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :media-engine:test --tests 'dev.sertas.engine.SystemAudioTrackTest'`
Expected: FAIL — `SystemAudioTrack` и `WebRtcEngine.createAudioTrack` не существуют.

- [ ] **Step 3: Add `createAudioTrack` to `WebRtcEngine`**

В `WebRtcEngine.java` после метода `createVideoTrack` добавьте (импорты `AudioTrack`, `AudioTrackSource` уже есть):

```java
    /** Аудио-трек из произвольного источника (микрофон, кастомный push-источник). */
    public AudioTrack createAudioTrack(String label, AudioTrackSource source) {
        return factory.createAudioTrack(label, source);
    }
```

- [ ] **Step 4: Write `SystemAudioTrack`**

```java
package dev.sertas.engine;

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.sertas.engine.SystemAudioProvider.PcmSink;
import dev.sertas.media.AudioFormatConverter;

/**
 * Трек звука демонстрации: владеет {@link CustomAudioSource} + {@link AudioTrack}
 * с меткой {@value #LABEL}. Принимает планарный Float32 от провайдера, ре-фреймит
 * в 10мс ({@link Pcm10msReframer}), конвертирует в S16 interleaved
 * ({@link AudioFormatConverter}) и пушит в источник. До {@link #start} трек выключен.
 *
 * <p>Звук демонстрации идёт мимо APM (без шумодава/AGC) — стерео-музыкальный
 * профиль навешивается SDP-munging'ом по метке трека (см. {@code MeshCoordinator}).
 */
public final class SystemAudioTrack implements PcmSink {

    public static final String LABEL = "screen-audio";

    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;

    private final CustomAudioSource source = new CustomAudioSource();
    private final AudioTrack track;
    private final Pcm10msReframer reframer = new Pcm10msReframer(48_000);

    private SystemAudioProvider provider;

    public SystemAudioTrack(WebRtcEngine engine) {
        this.track = engine.createAudioTrack(LABEL, source);
        this.track.setEnabled(false);
    }

    /** Трек для добавления в меш (вызывать до {@link #start}). */
    public AudioTrack track() {
        return track;
    }

    /** Включить: открыть трек и запустить провайдер захвата. */
    public synchronized void start(SystemAudioProvider provider) {
        this.provider = provider;
        track.setEnabled(true);
        provider.start(this);
    }

    /** Выключить: остановить провайдер и закрыть трек. */
    public synchronized void stop() {
        if (provider != null) {
            provider.stop();
            provider = null;
        }
        track.setEnabled(false);
    }

    @Override
    public void onPcm(float[] left, float[] right, int sampleRate) {
        for (Pcm10msReframer.Block b : reframer.offer(left, right)) {
            byte[] pcm = AudioFormatConverter.float32PlanarToS16Interleaved(b.left(), b.right());
            source.pushAudio(pcm, BITS_PER_SAMPLE, sampleRate, CHANNELS, b.left().length);
        }
    }

    /**
     * Освободить ресурсы. Нативная очистка трека/источника происходит при
     * {@code WebRtcEngine.dispose()} (factory.dispose) — отдельный
     * {@code source.dispose()} здесь не вызываем, иначе «reference still around».
     */
    public void dispose() {
        stop();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :media-engine:test --tests 'dev.sertas.engine.SystemAudioTrackTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add media-engine/src/main/java/dev/sertas/engine/WebRtcEngine.java \
        media-engine/src/main/java/dev/sertas/engine/SystemAudioTrack.java \
        media-engine/src/test/java/dev/sertas/engine/SystemAudioTrackTest.java
git commit -m "feat(engine): SystemAudioTrack — CustomAudioSource + трек screen-audio + push"
```

---

## Task 5: Подключить SDP-munging в меш + проверка на реальном offer

**Files:**
- Modify: `media-engine/src/main/java/dev/sertas/engine/MeshCoordinator.java`
- Test: `media-engine/src/test/java/dev/sertas/engine/ScreenAudioTrackOfferTest.java`

Этот тест — **главный де-риск**: подтверждает, что webrtc-java кладёт метку трека `screen-audio` в `a=msid` реального SDP (иначе подбор секции по метке не сработает).

- [ ] **Step 1: Write the failing test**

```java
package dev.sertas.engine;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.sertas.media.OpusSdpMunger;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Реальный offer с двумя аудио-треками (микрофон + screen-audio). Проверяет, что
 * метка трека screen-audio попадает в a=msid и что m-line-aware munging навешивает
 * музыкальный профиль ТОЛЬКО на секцию демо-звука, не трогая микрофон.
 */
class ScreenAudioTrackOfferTest {

    @Test
    void onlyScreenAudioSectionGetsMusicProfileInRealOffer() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerConnectionFactory factory = engine.factory();

        AudioTrackSource micSource = factory.createAudioSource(new AudioOptions());
        AudioTrack mic = factory.createAudioTrack("mic", micSource);
        CustomAudioSource screenSource = new CustomAudioSource();
        AudioTrack screen = factory.createAudioTrack(SystemAudioTrack.LABEL, screenSource);

        RTCPeerConnection pc = engine.createPeerConnection(new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
            }
        });

        try {
            pc.addTrack(mic, List.of("sertas"));
            pc.addTrack(screen, List.of("sertas"));

            CompletableFuture<String> sdp = new CompletableFuture<>();
            pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    sdp.complete(description.sdp);
                }

                @Override
                public void onFailure(String error) {
                    sdp.completeExceptionally(new IllegalStateException(error));
                }
            });

            String offer = sdp.get(10, TimeUnit.SECONDS);
            assertTrue(offer.contains(SystemAudioTrack.LABEL),
                    "метка трека не попала в SDP (a=msid) — подбор секции по метке не сработает:\n" + offer);

            String munged = OpusSdpMunger.applyMusicProfileToTrack(offer, SystemAudioTrack.LABEL);
            assertNotEquals(offer, munged, "munger ничего не изменил");
            assertEquals(1, count(munged, "stereo=1"), "должна мунжиться ровно одна аудио-секция:\n" + munged);
            assertTrue(sectionContaining(munged, SystemAudioTrack.LABEL).contains("stereo=1"),
                    "секция screen-audio должна быть stereo");
            assertFalse(sectionContaining(munged, "mic").contains("stereo=1"),
                    "микрофон не должен мунжиться");
        } finally {
            pc.close();
            engine.dispose();
        }
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    private static String sectionContaining(String sdp, String marker) {
        int mark = sdp.indexOf("msid:sertas " + marker);
        int start = sdp.lastIndexOf("m=", mark);
        int next = sdp.indexOf("\r\nm=", mark);
        if (next < 0) {
            next = sdp.indexOf("\nm=", mark);
        }
        int end = next < 0 ? sdp.length() : next;
        return sdp.substring(start, end);
    }
}
```

- [ ] **Step 2: Run test to verify it fails (or reveals msid format)**

Run: `./gradlew :media-engine:test --tests 'dev.sertas.engine.ScreenAudioTrackOfferTest'`
Expected: FAIL.
**If it fails on `метка трека не попала в SDP`:** webrtc-java присвоил треку сгенерированный id вместо метки. Запасной план — мунжить по порядку секций (последняя аудио-секция = screen-audio, т.к. добавляется второй): измените `applyMusicProfileToTrack` на выбор по индексу аудио-секции, а тесты — на проверку порядка. Зафиксируйте находку комментарием в `SystemAudioTrack`. Этот тест существует именно чтобы поймать это рано.

- [ ] **Step 3: Wire the SDP transform into `MeshCoordinator`**

В `MeshCoordinator.java` добавьте импорты:

```java
import dev.sertas.media.OpusSdpMunger;
import java.util.function.UnaryOperator;
```

Добавьте константу рядом с другими полями класса:

```java
    /** Музыкальный профиль Opus навешивается только на секцию трека звука демо. */
    private static final UnaryOperator<String> SCREEN_AUDIO_MUSIC =
            sdp -> OpusSdpMunger.applyMusicProfileToTrack(sdp, SystemAudioTrack.LABEL);
```

В методе `newSession` замените конструктор `PeerSession`:

```java
        PeerSession session = new PeerSession(engine, new PeerSession.Signals() {
```

на трёхаргументный (трансформ — последний аргумент). Найдите закрывающую скобку анонимного `Signals` (`});` в конце `newSession`) и вставьте трансформ перед ней:

```java
        PeerSession session = new PeerSession(engine, new PeerSession.Signals() {
            // ... тело Signals без изменений ...
        }, SCREEN_AUDIO_MUSIC);
```

- [ ] **Step 4: Run the offer test + full module tests**

Run: `./gradlew :media-engine:test --tests 'dev.sertas.engine.ScreenAudioTrackOfferTest'`
Expected: PASS.

Run: `./gradlew :media-engine:test`
Expected: PASS — все существующие тесты меша зелёные (munging не ломает offer/answer; mic-only секции не содержат метку → не трогаются).

- [ ] **Step 5: Commit**

```bash
git add media-engine/src/main/java/dev/sertas/engine/MeshCoordinator.java \
        media-engine/src/test/java/dev/sertas/engine/ScreenAudioTrackOfferTest.java
git commit -m "feat(engine): SDP-munging музыкального профиля для секции screen-audio в меше"
```

---

## Task 6: End-to-end headless — тон проходит от источника к приёмнику

**Files:**
- Test: `media-engine/src/test/java/dev/sertas/engine/ScreenAudioLoopbackTest.java`

Два `PeerSession` соединяются по loopback (как `PeerSessionLoopbackTest`); отправитель добавляет `SystemAudioTrack` и пушит синус; приёмник вешает `AudioTrackSink` на удалённый трек и ловит ненулевой звук. Валидирует весь путь передачи звука демо headless.

- [ ] **Step 1: Write the test**

```java
package dev.sertas.engine;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScreenAudioLoopbackTest {

    @Test
    void tonePushedBySenderReachesReceiverSink() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerSession[] holder = new PeerSession[2];
        CountDownLatch heardNonSilence = new CountDownLatch(1);

        PeerSession a = new PeerSession(engine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                holder[1].onRemoteDescription(d);
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                holder[1].onRemoteIceCandidate(c);
            }
        });

        PeerSession b = new PeerSession(engine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                holder[0].onRemoteDescription(d);
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                holder[0].onRemoteIceCandidate(c);
            }

            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().getTrack();
                if (track instanceof AudioTrack audio) {
                    audio.addSink(new AudioTrackSink() {
                        @Override
                        public void onData(byte[] data, int bitsPerSample, int sampleRate,
                                           int channels, int frames) {
                            for (byte sample : data) {
                                if (sample != 0) {
                                    heardNonSilence.countDown();
                                    return;
                                }
                            }
                        }
                    });
                }
            }
        });

        holder[0] = a;
        holder[1] = b;

        SystemAudioTrack sender = new SystemAudioTrack(engine);
        a.addTrack(sender.track());
        a.createOffer();

        // Дать соединению установиться, затем включить синус.
        Thread.sleep(3_000);
        sender.start(new FakeSystemAudioProvider(440));

        assertTrue(heardNonSilence.await(25, TimeUnit.SECONDS),
                "приёмник не получил ненулевой звук — путь передачи демо-звука не работает");

        sender.stop();
        a.close();
        b.close();
        engine.dispose();
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :media-engine:test --tests 'dev.sertas.engine.ScreenAudioLoopbackTest'`
Expected: PASS в пределах ~10–15с.
**Если тест флакает/висит:** детерминированное покрытие пути уже дают Задачи 1–5 (рефреймер, конвертер, реальный offer со стерео-секцией). В этом случае оставьте тест помеченным `@org.junit.jupiter.api.Tag("integration")` и не блокируйте им сборку; зафиксируйте причину комментарием. Не удаляйте без необходимости — это единственная headless-проверка сквозного звука.

- [ ] **Step 3: Commit**

```bash
git add media-engine/src/test/java/dev/sertas/engine/ScreenAudioLoopbackTest.java
git commit -m "test(engine): сквозной headless-тест передачи звука демо (тон → sink приёмника)"
```

---

## Task 7: `CallController` — создать трек звука демо + start/stop

**Files:**
- Modify: `app-client/src/main/java/dev/sertas/app/CallController.java`

Без юнит-теста (UI-интеграция; проверка вручную в Задаче 8). В Фазе A источник — синус `FakeSystemAudioProvider(440)`; Фаза B заменит его на `MacSystemAudioCapture`.

- [ ] **Step 1: Add imports**

В `CallController.java` к блоку импортов `dev.sertas.engine.*` добавьте:

```java
import dev.sertas.engine.FakeSystemAudioProvider;
import dev.sertas.engine.SystemAudioTrack;
```

- [ ] **Step 2: Add fields**

Рядом с `private ScreenCaptureSource screen;` добавьте:

```java
    private SystemAudioTrack screenAudio;
    private boolean screenAudioOn = false;
```

- [ ] **Step 3: Create the track in `join()`**

В методе `join`, сразу после блока создания экранного трека (после `mesh.addLocalTrack(screenTrack);`), добавьте:

```java
        // Трек звука демонстрации согласуем сразу (как видео-экран), включаем позже.
        screenAudio = new SystemAudioTrack(engine);
        mesh.addLocalTrack(screenAudio.track());
```

- [ ] **Step 4: Add start/stop/query methods**

После метода `isSharing()` добавьте:

```java
    /** Включить передачу системного звука демонстрации зрителям. */
    public void startScreenAudio() {
        if (screenAudio != null) {
            // Фаза A: временный источник-синус 440Гц. Фаза B заменит на
            // нативный ScreenCaptureKit (MacSystemAudioCapture).
            screenAudio.start(new FakeSystemAudioProvider(440));
            screenAudioOn = true;
        }
    }

    public void stopScreenAudio() {
        if (screenAudio != null) {
            screenAudio.stop();
        }
        screenAudioOn = false;
    }

    public boolean isScreenAudioOn() {
        return screenAudioOn;
    }
```

- [ ] **Step 5: Clean up in `leave()`**

В методе `leave`, перед `if (mesh != null) {`, добавьте остановку звука демо:

```java
        if (screenAudio != null) {
            screenAudio.stop();
            screenAudio = null;
        }
        screenAudioOn = false;
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :app-client:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app-client/src/main/java/dev/sertas/app/CallController.java
git commit -m "feat(app): трек звука демонстрации в CallController (источник Фазы A — синус)"
```

---

## Task 8: UI-кнопка «Звук демонстрации»

**Files:**
- Modify: `app-client/src/main/java/dev/sertas/app/ui/CallView.java`
- Modify: `app-client/src/main/java/dev/sertas/app/SertasApp.java`

- [ ] **Step 1: Add the toggle to `CallView`**

В `CallView.java` рядом с `private final ToggleButton share = ...` добавьте:

```java
    private final ToggleButton screenAudio = new ToggleButton("Звук демонстрации");
```

Замените строку создания панели управления:

```java
        HBox controls = new HBox(10, mute, share, leave);
```

на:

```java
        HBox controls = new HBox(10, mute, share, screenAudio, leave);
```

После метода `shareButton()` добавьте геттер:

```java
    public ToggleButton screenAudioButton() {
        return screenAudio;
    }
```

- [ ] **Step 2: Wire the toggle in `SertasApp`**

В `SertasApp.showCall`, после блока слушателя `call.shareButton()...`, добавьте слушатель звука демо и привязку доступности к демонстрации:

```java
        // Звук демо доступен только во время демонстрации экрана.
        call.screenAudioButton().disableProperty().bind(call.shareButton().selectedProperty().not());
        call.screenAudioButton().selectedProperty().addListener((obs, was, on) -> {
            if (on) {
                controller.startScreenAudio();
                call.screenAudioButton().setText("Звук демо вкл");
            } else {
                controller.stopScreenAudio();
                call.screenAudioButton().setText("Звук демонстрации");
            }
        });
```

В том же методе, в слушателе `call.shareButton()`, в ветке выключения демонстрации (`else { controller.stopScreenShare(); ... }`) добавьте сброс звука демо, чтобы он не «завис» включённым:

```java
                controller.stopScreenShare();
                call.shareButton().setText("Демонстрация");
                call.screenAudioButton().setSelected(false);
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app-client:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification (two clients, real run)**

Запуск двух клиентов локально (см. README/DEPLOY — `./gradlew :app-client:run`, либо два собранных бандла). Сценарий:
1. Оба входят в одну комнату.
2. Клиент №1 жмёт «Демонстрация» → кнопка «Звук демонстрации» становится доступной.
3. Клиент №1 жмёт «Звук демонстрации» → клиент №2 слышит тон 440Гц.
4. Клиент №1 выключает «Звук демонстрации» → тон пропадает.
5. Клиент №1 выключает «Демонстрацию» → кнопка звука деактивируется и сбрасывается.

Expected: тон слышен только при включённом тумблере; нет крашей; нет двойного звука.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew test`
Expected: PASS — все модули зелёные (30+ существующих тестов + новые Фазы A).

- [ ] **Step 6: Commit**

```bash
git add app-client/src/main/java/dev/sertas/app/ui/CallView.java \
        app-client/src/main/java/dev/sertas/app/SertasApp.java
git commit -m "feat(app): кнопка «Звук демонстрации» (активна во время показа)"
```

---

## Definition of Done (Фаза A)

- [ ] `Pcm10msReframer`, `OpusSdpMunger.applyMusicProfileToTrack`, `FakeSystemAudioProvider`, `SystemAudioTrack` — с зелёными юнит-тестами.
- [ ] Реальный offer: секция `screen-audio` — стерео-музыкальный Opus, `mic` — нетронутый mono-VOIP (`ScreenAudioTrackOfferTest`).
- [ ] Сквозной headless-тест передачи тона (`ScreenAudioLoopbackTest`) проходит (или помечен `integration` с зафиксированной причиной).
- [ ] UI-тумблер «Звук демонстрации» активен во время показа; вручную подтверждено — зритель слышит тон.
- [ ] `./gradlew test` — всё зелёное.

**Следующий шаг — Фаза B:** заменить `new FakeSystemAudioProvider(440)` в `CallController.startScreenAudio()` на `MacSystemAudioCapture` (нативный ScreenCaptureKit `.dylib` + JNI). Интерфейс `SystemAudioProvider` уже готов — натив подключается одной строкой.
```
