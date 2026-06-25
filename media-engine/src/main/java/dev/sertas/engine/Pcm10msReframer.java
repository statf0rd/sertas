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
