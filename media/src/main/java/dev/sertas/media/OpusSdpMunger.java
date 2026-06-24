package dev.sertas.media;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Переписывает {@code a=fmtp}-строку Opus под «музыкальный» профиль для трека
 * демонстрации экрана: стерео, высокий битрейт, без DTX. Так звук демонстрации
 * (музыка/игра) не уводится в SILK и не зажимается по битрейту.
 *
 * <p>Применяется к локальному SDP (offer/answer) перед {@code setLocalDescription}.
 * Если Opus в SDP не найден — возвращает исходный SDP без изменений.
 */
public final class OpusSdpMunger {

    private OpusSdpMunger() {}

    /** Параметры «музыкального» профиля в порядке вывода. */
    private static final Map<String, String> MUSIC = new LinkedHashMap<>();

    static {
        MUSIC.put("minptime", "10");
        MUSIC.put("useinbandfec", "1");
        MUSIC.put("stereo", "1");
        MUSIC.put("sprop-stereo", "1");
        MUSIC.put("maxaveragebitrate", "192000");
        MUSIC.put("usedtx", "0");
        MUSIC.put("cbr", "0");
    }

    private static final Pattern RTPMAP = Pattern.compile("(?m)^a=rtpmap:(\\d+)\\s+opus/48000/2");

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

    private static Map<String, String> parse(String s) {
        Map<String, String> p = new LinkedHashMap<>();
        for (String kv : s.split(";")) {
            String t = kv.trim();
            if (t.isEmpty()) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq < 0) {
                p.put(t, "");
            } else {
                p.put(t.substring(0, eq), t.substring(eq + 1));
            }
        }
        return p;
    }

    private static String render(Map<String, String> p) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (b.length() > 0) {
                b.append(';');
            }
            b.append(e.getKey());
            if (!e.getValue().isEmpty()) {
                b.append('=').append(e.getValue());
            }
        }
        return b.toString();
    }
}
