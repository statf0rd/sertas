package dev.sertas.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Программный микшер для стороны слушателя: суммирует несколько источников
 * (голос участника / звук его демо) с независимой громкостью на источник, чтобы
 * разные звуки не «накладывались» неконтролируемо. Канонический внутренний формат —
 * 48кГц стерео float, interleaved, [-1, 1].
 *
 * <p>Чистая логика, без нативного кода — полностью юнит-тестируема. Конвертация
 * форматов (S16↔float, моно→стерео, ресэмпл) делается на краях: вход —
 * {@code AudioTrackSink}, выход — {@code AudioSource.onPlaybackData} в обвязке ADM.
 *
 * <p>Потокобезопасен: {@code submit} зовётся с потоков WebRTC-сінков,
 * {@code pull} — с аудио-потока воспроизведения.
 */
public final class SoftwareMixer {

    private static final int CHANNELS = 2;
    private static final int CAP_FRAMES = 24_000; // 0.5с @48к — ограничение латентности/роста

    private final Map<String, Source> sources = new LinkedHashMap<>();

    public synchronized void addSource(String id) {
        sources.computeIfAbsent(id, k -> new Source());
    }

    public synchronized void removeSource(String id) {
        sources.remove(id);
    }

    public synchronized boolean hasSource(String id) {
        return sources.containsKey(id);
    }

    /** Громкость источника (0..N). Отрицательная зажимается в 0. */
    public synchronized void setGain(String id, float gain) {
        Source s = sources.get(id);
        if (s != null) {
            s.gain = Math.max(0f, gain);
        }
    }

    public synchronized float gain(String id) {
        Source s = sources.get(id);
        return s == null ? 0f : s.gain;
    }

    /** Добавить PCM источника (канонический 48к стерео float, interleaved). */
    public synchronized void submit(String id, float[] interleavedStereo) {
        Source s = sources.get(id);
        if (s != null) {
            s.append(interleavedStereo);
        }
    }

    /**
     * Заполнить {@code out} ({@code frames*2} interleaved float) суммой источников
     * с учётом громкости; недостача данных источника → тишина. Возврат — {@code frames}.
     */
    public synchronized int pull(float[] out, int frames) {
        int n = frames * CHANNELS;
        for (int i = 0; i < n; i++) {
            out[i] = 0f;
        }
        for (Source s : sources.values()) {
            s.mixInto(out, frames);
        }
        for (int i = 0; i < n; i++) {
            float v = out[i];
            out[i] = v > 1f ? 1f : (v < -1f ? -1f : v);
        }
        return frames;
    }

    private static final class Source {
        float gain = 1f;
        private float[] buf = new float[0];
        private int size; // валидных сэмплов (interleaved)

        void append(float[] in) {
            ensure(size + in.length);
            System.arraycopy(in, 0, buf, size, in.length);
            size += in.length;
            int cap = CAP_FRAMES * CHANNELS;
            if (size > cap) {
                int drop = size - cap;
                System.arraycopy(buf, drop, buf, 0, size - drop);
                size -= drop;
            }
        }

        void mixInto(float[] out, int frames) {
            int want = frames * CHANNELS;
            int avail = Math.min(want, size);
            for (int i = 0; i < avail; i++) {
                out[i] += gain * buf[i];
            }
            if (avail > 0) {
                System.arraycopy(buf, avail, buf, 0, size - avail);
                size -= avail;
            }
        }

        void ensure(int need) {
            if (buf.length >= need) {
                return;
            }
            int cap = Math.max(need, 1024);
            float[] nb = new float[cap];
            System.arraycopy(buf, 0, nb, 0, size);
            buf = nb;
        }
    }
}
