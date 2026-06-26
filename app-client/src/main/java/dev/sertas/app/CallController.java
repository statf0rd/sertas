package dev.sertas.app;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.sertas.app.ui.ParticipantModel;
import dev.sertas.app.ui.VideoTile;
import dev.sertas.engine.JavaSoundDemoPlayer;
import dev.sertas.engine.MacSystemAudioCapture;
import dev.sertas.engine.MeshCoordinator;
import dev.sertas.engine.MeshListener;
import dev.sertas.engine.RemoteAudioMixer;
import dev.sertas.engine.ScreenAudioConnection;
import dev.sertas.engine.ScreenCaptureSource;
import dev.sertas.engine.SystemAudioProvider;
import dev.sertas.engine.SystemAudioTrack;
import dev.sertas.engine.WebRtcEngine;
import dev.sertas.engine.WinSystemAudioCapture;
import dev.sertas.media.OpusSdpMunger;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.layout.FlowPane;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Связывает {@link MeshCoordinator} с JavaFX-UI. Колбэки меша приходят на
 * нативных/WS-потоках и маршалятся на FX-поток через {@link Platform#runLater}.
 */
public final class CallController implements MeshListener {

    private final ObservableList<ParticipantModel> participants = FXCollections.observableArrayList();
    private final FlowPane videoPane = new FlowPane(12, 12);
    private final Map<String, ParticipantModel> byId = new HashMap<>();   // только FX-поток
    private final Map<String, VideoTile> tiles = new HashMap<>();         // только FX-поток
    private final Set<String> wiredGains = new HashSet<>();               // (peerId:Kind) с навешанным слушателем; FX-поток

    /** Музыкальный профиль Opus для секции трека звука демо. */
    private static final UnaryOperator<String> SCREEN_AUDIO_MUSIC =
            sdp -> OpusSdpMunger.applyMusicProfileToTrack(sdp, SystemAudioTrack.LABEL);

    private WebRtcEngine engine;
    private MeshCoordinator mesh;
    private AudioTrack mic;
    private ScreenCaptureSource screen;
    private RemoteAudioMixer audioMixer;
    private ParticipantModel self;

    // Звук демо — ОТДЕЛЬНЫЙ headless-движок (нет реального ADM → push не гоняется),
    // сигналинг поверх control data-channel главного соединения, воспроизведение
    // у зрителя через javax.sound. См. ScreenAudioConnection / диагноз гонки.
    private WebRtcEngine audioEngine;
    private SystemAudioTrack screenAudio;
    private JavaSoundDemoPlayer demoPlayer;
    private final Map<String, ScreenAudioConnection> screenAudioConns = new ConcurrentHashMap<>();
    private boolean micMuted = false;
    private volatile boolean sharing = false; // пишется из фонового потока старта показа
    private boolean screenAudioOn = false;

    public CallController() {
        videoPane.setPadding(new Insets(12));
    }

    public ObservableList<ParticipantModel> participants() {
        return participants;
    }

    /** Панель с видео-плитками удалённых участников. */
    public FlowPane videoPane() {
        return videoPane;
    }

    /** Создать движок, треки (микрофон + экран) и войти в комнату. */
    public void join(String url, String room, String name) {
        engine = new WebRtcEngine();
        mesh = new MeshCoordinator(engine, this);

        // Per-источниковый микшер у слушателя ВЫКЛЮЧЕН по умолчанию: на реальном
        // железе кастомный setAudioSource заменяет штатный playout libwebrtc и не
        // выдаёт звук (голос пропадает). Включается -Dsertas.mixer=on — после
        // доводки playout на железе станет дефолтом. Когда выключен — audioMixer
        // остаётся null, и звук идёт штатным путём libwebrtc.
        if ("on".equalsIgnoreCase(System.getProperty("sertas.mixer", "off"))) {
            audioMixer = new RemoteAudioMixer();
            engine.audioDeviceModule().setAudioSource(audioMixer);
        }

        mic = engine.createMicTrack();
        mesh.addLocalTrack(mic);

        // Экранный трек согласуем сразу (но не захватываем) — чтобы потом начать
        // демонстрацию без повторной переговорки.
        screen = new ScreenCaptureSource();
        VideoTrack screenTrack = engine.createVideoTrack("screen", screen.source());
        mesh.addLocalTrack(screenTrack);

        // Звук демо — на ОТДЕЛЬНОМ headless-движке (без реального ADM), отдельным
        // соединением (см. ScreenAudioConnection), чтобы pushAudio не гонялся с
        // реальным ADM голоса (диагноз — гонка в audio_send_stream → краш). Трек НЕ
        // добавляем в главный меш. Флаг -Dsertas.demoaudio=on (пока on для проверки).
        if ("on".equalsIgnoreCase(System.getProperty("sertas.demoaudio", "off"))) {
            audioEngine = WebRtcEngine.headless();
            screenAudio = new SystemAudioTrack(audioEngine);
            demoPlayer = new JavaSoundDemoPlayer();
        }

        Platform.runLater(() -> {
            self = new ParticipantModel("self", name);
            self.setState("вы");
            participants.add(0, self);
        });

        mesh.start(url, room, name);
    }

    public void setMicMuted(boolean muted) {
        micMuted = muted;
        if (mic != null) {
            mic.setEnabled(!muted);
        }
    }

    public boolean isMicMuted() {
        return micMuted;
    }

    /**
     * Начать демонстрацию экрана (основной экран). Захват/энумерация экранов —
     * тяжёлая нативная работа (TCC, ScreenCaptureKit), поэтому в ОТДЕЛЬНОМ потоке:
     * иначе FX-поток блокируется и UI «лагает» при нажатии «Демонстрация».
     */
    public void startScreenShare() {
        ScreenCaptureSource src = screen;
        if (src == null) {
            return;
        }
        new Thread(() -> {
            try {
                List<dev.onvoid.webrtc.media.video.desktop.DesktopSource> screens = ScreenCaptureSource.screens();
                if (screens.isEmpty()) {
                    onError(new IllegalStateException("нет доступных экранов (проверьте разрешение)"));
                    return;
                }
                src.select(screens.get(0).id, ScreenCaptureSource.Quality.BALANCED);
                src.start();
                sharing = true;
            } catch (RuntimeException e) {
                onError(e);
            }
        }, "screen-share-start").start();
    }

    public void stopScreenShare() {
        if (screen != null) {
            try {
                screen.stop();
            } catch (RuntimeException ignored) {
                // источник уже остановлен
            }
        }
        sharing = false;
    }

    public boolean isSharing() {
        return sharing;
    }

    /** Включить передачу системного звука демонстрации зрителям. */
    public void startScreenAudio() {
        if (screenAudio == null) {
            return;
        }
        SystemAudioProvider provider = nativeSystemAudioProvider();
        if (provider == null) {
            onError(new IllegalStateException(
                    "нативный захват звука демо недоступен на этой платформе "
                            + "(нужен dylib/dll — см. scripts/build-*-audio-*)"));
            return;
        }
        try {
            screenAudio.start(provider);
            screenAudioOn = true;
        } catch (RuntimeException e) {
            onError(e);
        }
    }

    /** Нативный провайдер системного звука под текущую ОС (null — недоступен). */
    private static SystemAudioProvider nativeSystemAudioProvider() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return MacSystemAudioCapture.isAvailable() ? new MacSystemAudioCapture() : null;
        }
        if (os.contains("win")) {
            return WinSystemAudioCapture.isAvailable() ? new WinSystemAudioCapture() : null;
        }
        return null;
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

    public void leave() {
        stopScreenShare();
        if (screenAudio != null) {
            screenAudio.stop();
            screenAudio = null;
        }
        screenAudioOn = false;
        screenAudioConns.values().forEach(ScreenAudioConnection::close);
        screenAudioConns.clear();
        if (demoPlayer != null) {
            demoPlayer.stop();
            demoPlayer = null;
        }
        if (mesh != null) {
            mesh.stop();
            mesh = null;
        }
        if (engine != null) {
            engine.dispose();
            engine = null;
        }
        if (audioEngine != null) {
            audioEngine.dispose();
            audioEngine = null;
        }
        mic = null;
        screen = null;
        audioMixer = null;
        Platform.runLater(() -> {
            tiles.values().forEach(VideoTile::dispose);
            tiles.clear();
            videoPane.getChildren().clear();
            participants.clear();
            byId.clear();
            wiredGains.clear();
            self = null;
        });
    }

    @Override
    public void onPeerJoined(String peerId, String name) {
        Platform.runLater(() -> {
            ParticipantModel m = byId.get(peerId);
            if (m == null) {
                m = new ParticipantModel(peerId, name);
                byId.put(peerId, m);
                participants.add(m);
            } else {
                m.setName(name);
            }
        });
    }

    @Override
    public void onPeerLeft(String peerId) {
        Platform.runLater(() -> {
            ParticipantModel m = byId.remove(peerId);
            if (m != null) {
                participants.remove(m);
            }
            VideoTile tile = tiles.remove(peerId);
            if (tile != null) {
                videoPane.getChildren().remove(tile.node());
                tile.dispose();
            }
            if (audioMixer != null) {
                audioMixer.detach(peerId);
            }
            wiredGains.remove(peerId + ":" + RemoteAudioMixer.Kind.VOICE);
            wiredGains.remove(peerId + ":" + RemoteAudioMixer.Kind.DEMO);
            ScreenAudioConnection conn = screenAudioConns.remove(peerId);
            if (conn != null) {
                conn.close();
            }
        });
    }

    @Override
    public void onPeerState(String peerId, RTCPeerConnectionState state) {
        Platform.runLater(() -> {
            ParticipantModel m = byId.get(peerId);
            if (m != null) {
                m.setState(stateLabel(state));
            }
        });
    }

    @Override
    public void onControlChannel(String peerId, RTCDataChannel channel, boolean initiator) {
        if (audioEngine == null || screenAudio == null) {
            return; // звук демо выключен
        }
        ScreenAudioConnection conn = new ScreenAudioConnection(
                audioEngine, channel, initiator, screenAudio.track(),
                this::onRemoteScreenAudio, SCREEN_AUDIO_MUSIC);
        ScreenAudioConnection old = screenAudioConns.put(peerId, conn);
        if (old != null) {
            old.close();
        }
    }

    /** Удалённый трек звука демо (на отдельном соединении) → воспроизведение через javax.sound. */
    private void onRemoteScreenAudio(RTCRtpTransceiver transceiver) {
        JavaSoundDemoPlayer player = demoPlayer;
        if (player == null) {
            return;
        }
        MediaStreamTrack track = transceiver.getReceiver().getTrack();
        if (track instanceof AudioTrack audio) {
            audio.addSink((data, bitsPerSample, sampleRate, channels, frames) -> {
                if (bitsPerSample == 16 && frames > 0) {
                    player.offer(data, sampleRate, channels);
                }
            });
        }
    }

    @Override
    public void onRemoteTrack(String peerId, RTCRtpTransceiver transceiver) {
        Platform.runLater(() -> {
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            if (track == null) {
                return;
            }
            if (MediaStreamTrack.AUDIO_TRACK_KIND.equals(track.getKind())) {
                attachRemoteAudio(peerId, (AudioTrack) track);
                return;
            }
            if (!MediaStreamTrack.VIDEO_TRACK_KIND.equals(track.getKind())) {
                return;
            }
            VideoTile old = tiles.remove(peerId);
            if (old != null) {
                videoPane.getChildren().remove(old.node());
                old.dispose();
            }
            VideoTile tile = new VideoTile((VideoTrack) track);
            tiles.put(peerId, tile);
            videoPane.getChildren().add(tile.node());
        });
    }

    /** Подключить удалённый аудио-трек к микшеру и связать его громкость со слайдером участника. */
    private void attachRemoteAudio(String peerId, AudioTrack audio) {
        if (audioMixer == null) {
            return;
        }
        audioMixer.attach(peerId, audio); // идемпотентно
        RemoteAudioMixer.Kind kind = RemoteAudioMixer.kindOf(audio);
        ParticipantModel m = byId.get(peerId);
        String key = peerId + ":" + kind;
        // Слушатель вешаем один раз на (участник, вид) — иначе дубли при повторном onRemoteTrack.
        if (m != null && wiredGains.add(key)) {
            DoubleProperty gain = kind == RemoteAudioMixer.Kind.DEMO
                    ? m.demoGainProperty() : m.voiceGainProperty();
            audioMixer.setGain(peerId, kind, (float) gain.get());
            gain.addListener((obs, o, n) -> {
                RemoteAudioMixer mx = audioMixer; // снимок: после leave() поле null → no-op
                if (mx != null) {
                    mx.setGain(peerId, kind, n.floatValue());
                }
            });
        }
    }

    @Override
    public void onError(Throwable error) {
        System.err.println("mesh error: " + error);
    }

    private static String stateLabel(RTCPeerConnectionState state) {
        return switch (state) {
            case NEW -> "…";
            case CONNECTING -> "соединение…";
            case CONNECTED -> "на связи";
            case DISCONNECTED -> "отключён";
            case FAILED -> "ошибка";
            case CLOSED -> "закрыт";
        };
    }
}
