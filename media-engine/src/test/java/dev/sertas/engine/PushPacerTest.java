package dev.sertas.engine;

import org.junit.jupiter.api.Test;

import static dev.sertas.engine.PushPacer.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class PushPacerTest {

    @Test
    void waitsForPrimerBeforeFirstPush() {
        PushPacer p = new PushPacer(4, 50);
        assertEquals(SKIP, p.tick(1));
        assertEquals(SKIP, p.tick(3));
        assertEquals(DATA, p.tick(4));
        assertEquals(DATA, p.tick(3));
    }

    @Test
    void momentaryEmptyTickIsSkippedNotFilledWithSilence() {
        PushPacer p = new PushPacer(2, 50);
        assertEquals(DATA, p.tick(2));
        assertEquals(SKIP, p.tick(0));   // джиттер захвата — не тишина
        assertEquals(SKIP, p.tick(0));
        assertEquals(DATA, p.tick(1));   // данные пришли — сразу пушим
    }

    @Test
    void sustainedStarvationSendsSilenceThenRequiresReprime() {
        PushPacer p = new PushPacer(2, 3);
        assertEquals(DATA, p.tick(2));
        assertEquals(SKIP, p.tick(0));
        assertEquals(SKIP, p.tick(0));
        assertEquals(SILENCE, p.tick(0)); // третий пустой тик подряд → тишина
        assertEquals(SILENCE, p.tick(0));
        assertEquals(SILENCE, p.tick(1)); // один блок — праймер ещё не набран
        assertEquals(DATA, p.tick(2));    // праймер набран → данные
        assertEquals(SKIP, p.tick(0));    // счётчик голода сброшен
    }

    @Test
    void silenceFromColdStartWithoutSource() {
        PushPacer p = new PushPacer(4, 2);
        assertEquals(SKIP, p.tick(0));
        assertEquals(SILENCE, p.tick(0));
    }

    @Test
    void resetForgetsPrimer() {
        PushPacer p = new PushPacer(2, 50);
        assertEquals(DATA, p.tick(2));
        p.reset();
        assertEquals(SKIP, p.tick(1));
    }
}
