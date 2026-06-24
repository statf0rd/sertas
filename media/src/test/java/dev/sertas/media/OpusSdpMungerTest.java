package dev.sertas.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpusSdpMungerTest {

    private static final String SDP = String.join("\r\n",
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "");

    @Test
    void appliesStereoHighBitrateToOpusFmtp() {
        String out = OpusSdpMunger.applyMusicProfile(SDP);
        String fmtp = out.lines().filter(l -> l.startsWith("a=fmtp:111")).findFirst().orElseThrow();
        assertTrue(fmtp.contains("stereo=1"), fmtp);
        assertTrue(fmtp.contains("sprop-stereo=1"), fmtp);
        assertTrue(fmtp.contains("maxaveragebitrate=192000"), fmtp);
        assertTrue(fmtp.contains("usedtx=0"), fmtp);
        assertTrue(fmtp.contains("useinbandfec=1"), fmtp);
    }

    @Test
    void addsFmtpWhenMissing() {
        String noFmtp = "v=0\r\nm=audio 9 RTP 111\r\na=rtpmap:111 opus/48000/2\r\n";
        String out = OpusSdpMunger.applyMusicProfile(noFmtp);
        assertTrue(out.lines().anyMatch(l -> l.startsWith("a=fmtp:111") && l.contains("stereo=1")), out);
    }

    @Test
    void noOpusIsUnchanged() {
        String pcmu = "v=0\r\nm=audio 9 RTP 0\r\na=rtpmap:0 PCMU/8000\r\n";
        assertEquals(pcmu, OpusSdpMunger.applyMusicProfile(pcmu));
    }

    @Test
    void preservesExistingUnrelatedParams() {
        String out = OpusSdpMunger.applyMusicProfile(SDP);
        String fmtp = out.lines().filter(l -> l.startsWith("a=fmtp:111")).findFirst().orElseThrow();
        assertTrue(fmtp.contains("minptime=10"), fmtp);
    }
}
