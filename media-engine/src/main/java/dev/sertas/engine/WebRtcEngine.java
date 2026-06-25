package dev.sertas.engine;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSource;

import java.util.ArrayList;

/**
 * Владелец единственного {@link PeerConnectionFactory} на приложение. Создаёт
 * {@link RTCPeerConnection} с дефолтной конфигурацией (STUN) — по одному на пира
 * в P2P-меше. Нативные ресурсы освобождаются в {@link #dispose()}.
 */
public final class WebRtcEngine {

    private final PeerConnectionFactory factory;

    /** Боевой движок: фабрика с реальным аудио-устройством (микрофон/динамики). */
    public WebRtcEngine() {
        this.factory = new PeerConnectionFactory();
    }

    private WebRtcEngine(AudioDeviceModuleBase adm) {
        this.factory = new PeerConnectionFactory(adm);
    }

    /** Движок без реальных аудио-устройств — для тестов и сценариев push-only PCM. */
    public static WebRtcEngine headless() {
        return new WebRtcEngine(new HeadlessAudioDeviceModule());
    }

    public PeerConnectionFactory factory() {
        return factory;
    }

    public RTCPeerConnection createPeerConnection(PeerConnectionObserver observer) {
        return factory.createPeerConnection(defaultConfig(), observer);
    }

    /**
     * Локальный трек микрофона. {@code options} включают голосовой DSP
     * (эхоподавление/шумодав/AGC) — переключаемый в Фазе 4. По умолчанию всё
     * включено для чистого голоса в комнате с открытыми колонками.
     */
    public AudioTrack createMicTrack(AudioOptions options) {
        AudioTrackSource source = factory.createAudioSource(options);
        return factory.createAudioTrack("mic", source);
    }

    public AudioTrack createMicTrack() {
        AudioOptions options = new AudioOptions();
        options.echoCancellation = true;
        options.noiseSuppression = true;
        options.autoGainControl = true;
        options.highpassFilter = true;
        return createMicTrack(options);
    }

    /** Видео-трек из любого источника (камера, экран, кастомный push-источник). */
    public VideoTrack createVideoTrack(String label, VideoTrackSource source) {
        return factory.createVideoTrack(label, source);
    }

    /** Дефолтная конфигурация: STUN + (если задан) TURN из {@link IceServersConfig}. */
    public static RTCConfiguration defaultConfig() {
        RTCConfiguration cfg = new RTCConfiguration();
        cfg.iceServers = new ArrayList<>(IceServersConfig.resolve());
        return cfg;
    }

    public void dispose() {
        factory.dispose();
    }
}
