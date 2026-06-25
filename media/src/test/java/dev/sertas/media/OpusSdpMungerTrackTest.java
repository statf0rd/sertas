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
        // Ровно одна секция получила музыкальный профиль (маркер однозначен — в
        // отличие от "stereo=1", который как подстрока есть и в "sprop-stereo=1").
        assertEquals(1, count(out, "maxaveragebitrate=192000"), out);

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
