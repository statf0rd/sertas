package dev.sertas.engine;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Владелец единственного {@link PeerConnectionFactory} на приложение. Создаёт
 * {@link RTCPeerConnection} с дефолтной конфигурацией (STUN) — по одному на пира
 * в P2P-меше. Нативные ресурсы освобождаются в {@link #dispose()}.
 */
public final class WebRtcEngine {

    private final PeerConnectionFactory factory;
    private final AudioDeviceModuleBase adm;

    /**
     * Боевой движок: фабрика с реальным аудио-устройством (микрофон/динамики).
     *
     * <p>ADM и фабрика создаются на ОТДЕЛЬНОМ потоке, а не на потоке вызова. На
     * Windows {@code AudioDeviceModule} инициализирует COM (Core Audio), а поток
     * JavaFX уже держит COM в режиме STA — webrtc требует другой apartment и
     * падает с фатальным «Invalid COM thread model change» (RPC_E_CHANGED_MODE).
     * На свежем потоке COM ещё не инициализирован, и webrtc выставляет MTA сам.
     */
    public WebRtcEngine() {
        this(createOnInitThread(() -> {
            AudioDeviceModule adm = new AudioDeviceModule();
            avoidCommunicationsRole(adm);
            return adm;
        }));
    }

    /**
     * Windows: libwebrtc по умолчанию открывает микрофон и динамики через «устройство
     * связи» (роль {@code eCommunications}). Такие потоки Windows считает
     * «коммуникационными» и по настройке «Звук → Связь» приглушает все остальные
     * звуки на 80% — у каждого участника, как только ADM стартует (вход первого
     * пира). Выбираем те же устройства по умолчанию ЯВНО (по id из списка ADM):
     * поток открывается без роли связи, и приглушения нет. Если устройство в списке
     * не нашлось — ничего не меняем. {@code -Dsertas.audio.role=comm} — старое поведение.
     * Только на потоке инициализации (COM в режиме MTA).
     */
    private static void avoidCommunicationsRole(AudioDeviceModuleBase adm) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")
                || "comm".equalsIgnoreCase(System.getProperty("sertas.audio.role", ""))) {
            return;
        }
        try {
            selectExplicitly(MediaDevices.getDefaultAudioCaptureDevice(), adm.getRecordingDevices(),
                    adm::setRecordingDevice, "микрофон");
            selectExplicitly(MediaDevices.getDefaultAudioRenderDevice(), adm.getPlayoutDevices(),
                    adm::setPlayoutDevice, "динамики");
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            System.err.println("[audio] явный выбор устройств не удался, остаётся устройство связи: " + e);
        }
    }

    private static void selectExplicitly(AudioDevice def, List<AudioDevice> known,
                                         Consumer<AudioDevice> select, String what) {
        if (def == null) {
            System.err.println("[audio] " + what + ": устройство по умолчанию не определено");
            return;
        }
        for (AudioDevice d : known) {
            if (Objects.equals(d.getDescriptor(), def.getDescriptor())) {
                select.accept(d);
                System.err.println("[audio] " + what + ": явно выбрано «" + d.getName() + "» (без роли связи)");
                return;
            }
        }
        System.err.println("[audio] " + what + ": «" + def.getName() + "» нет в списке ADM — остаётся устройство связи");
    }

    private WebRtcEngine(AudioDeviceModuleBase adm) {
        this.adm = adm;
        this.factory = new PeerConnectionFactory(adm);
    }

    private WebRtcEngine(Parts parts) {
        this.adm = parts.adm;
        this.factory = parts.factory;
    }

    private record Parts(AudioDeviceModuleBase adm, PeerConnectionFactory factory) {}

    /** ADM + фабрика на отдельном потоке (см. {@link #WebRtcEngine()}). */
    private static Parts createOnInitThread(Supplier<AudioDeviceModuleBase> admFactory) {
        Parts[] holder = new Parts[1];
        RuntimeException[] failure = new RuntimeException[1];
        Thread init = new Thread(() -> {
            try {
                AudioDeviceModuleBase adm = admFactory.get();
                holder[0] = new Parts(adm, new PeerConnectionFactory(adm));
            } catch (RuntimeException e) {
                failure[0] = e;
            }
        }, "sertas-webrtc-init");
        init.start();
        try {
            init.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("прерван запуск WebRtcEngine", e);
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        return holder[0];
    }

    /**
     * Движок без реальных аудио-устройств: {@link HeadlessAudioDeviceModule} крутит
     * виртуальные потоки воспроизведения И захвата. Годится для ПРИЁМА (sink'и
     * удалённых треков получают PCM только когда идёт playout), но НЕ для отдачи
     * push-источника: поток захвата headless-ADM кормит тот же
     * {@code AudioSendStream}, что и {@code CustomAudioSource.pushAudio}, и libwebrtc
     * падает на {@code RTC_CHECK_RUNS_SERIALIZED} — см. {@link #pushOnly()}.
     */
    public static WebRtcEngine headless() {
        return new WebRtcEngine(new HeadlessAudioDeviceModule());
    }

    /**
     * Движок для ОТДАЧИ из push-источников ({@code CustomAudioSource}): ADM
     * {@code kDummyAudio} — у него нет ни потока захвата, ни воспроизведения,
     * поэтому единственный производитель кадров для {@code AudioSendStream} — наш
     * push-поток. Принимать звук на этом движке нельзя (playout не запускается,
     * sink'и удалённых треков молчат) — для приёма см. {@link #headless()}.
     */
    public static WebRtcEngine pushOnly() {
        return new WebRtcEngine(createOnInitThread(() -> new AudioDeviceModule(AudioLayer.kDummyAudio)));
    }

    public PeerConnectionFactory factory() {
        return factory;
    }

    /**
     * Аудио-модуль устройства. Нужен для {@code setAudioSource} — подачи
     * собственного микса (см. {@code RemoteAudioMixer}) в воспроизведение мимо
     * штатного микшера libwebrtc.
     */
    public AudioDeviceModuleBase audioDeviceModule() {
        return adm;
    }

    public RTCPeerConnection createPeerConnection(PeerConnectionObserver observer) {
        return createPeerConnection(observer, null);
    }

    /** Peer-connection с заданными ICE-серверами (от сервера); null/пусто → дефолт. */
    public RTCPeerConnection createPeerConnection(PeerConnectionObserver observer, List<RTCIceServer> iceServers) {
        RTCConfiguration cfg;
        if (iceServers == null || iceServers.isEmpty()) {
            cfg = defaultConfig();
        } else {
            cfg = new RTCConfiguration();
            cfg.iceServers = new ArrayList<>(iceServers);
        }
        return factory.createPeerConnection(cfg, observer);
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

    /** Аудио-трек из произвольного источника (микрофон, кастомный push-источник). */
    public AudioTrack createAudioTrack(String label, AudioTrackSource source) {
        return factory.createAudioTrack(label, source);
    }

    /** Дефолтная конфигурация: STUN + (если задан) TURN из {@link IceServersConfig}. */
    public static RTCConfiguration defaultConfig() {
        RTCConfiguration cfg = new RTCConfiguration();
        cfg.iceServers = new ArrayList<>(IceServersConfig.resolve());
        return cfg;
    }

    public void dispose() {
        factory.dispose();
        adm.dispose();
    }
}
