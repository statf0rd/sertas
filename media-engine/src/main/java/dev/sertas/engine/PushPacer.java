package dev.sertas.engine;

/**
 * Решение «что пушить на этом 10мс-тике» для {@link SystemAudioTrack}. Чистая логика,
 * без потоков и нативного кода.
 *
 * <p>Захват отдаёт блоки с джиттером (WASAPI + таймер Windows — пачками по 15–30мс).
 * Если на каждом пустом тике досылать тишину, за реальное время уходит БОЛЬШЕ
 * кадров, чем 100/с: у зрителя NetEq вынужден ускорять и выкидывать сэмплы —
 * слышно как треск. Поэтому:
 * <ul>
 *   <li>стартуем только после праймера из {@code primeBlocks} блоков;</li>
 *   <li>пустой тик — {@link Action#SKIP} (редкую 10мс-дырку NetEq скроет);</li>
 *   <li>лишь при длительном голоде ({@code starvedTicks} подряд) — {@link Action#SILENCE},
 *       чтобы поток жил при цифровой тишине источника (WASAPI loopback тогда не
 *       отдаёт буферов); после голода снова ждём праймер.</li>
 * </ul>
 */
final class PushPacer {

    enum Action { DATA, SKIP, SILENCE }

    private final int primeBlocks;
    private final int starvedTicks;
    private boolean primed;
    private int emptyTicks;

    PushPacer(int primeBlocks, int starvedTicks) {
        if (primeBlocks < 1 || starvedTicks < 1) {
            throw new IllegalArgumentException("primeBlocks/starvedTicks must be >= 1");
        }
        this.primeBlocks = primeBlocks;
        this.starvedTicks = starvedTicks;
    }

    /** @param available сколько готовых 10мс-блоков лежит в очереди на момент тика. */
    Action tick(int available) {
        if (!primed) {
            if (available >= primeBlocks) {
                primed = true;
            } else {
                return starve();
            }
        }
        if (available > 0) {
            emptyTicks = 0;
            return Action.DATA;
        }
        return starve();
    }

    private Action starve() {
        emptyTicks++;
        if (emptyTicks >= starvedTicks) {
            primed = false;
            return Action.SILENCE;
        }
        return Action.SKIP;
    }

    void reset() {
        primed = false;
        emptyTicks = 0;
    }
}
