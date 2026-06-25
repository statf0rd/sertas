package dev.sertas.app;

import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.sertas.app.ui.ParticipantModel;
import dev.sertas.app.ui.VideoTile;
import dev.sertas.engine.FakeSystemAudioProvider;
import dev.sertas.engine.MeshCoordinator;
import dev.sertas.engine.MeshListener;
import dev.sertas.engine.ScreenCaptureSource;
import dev.sertas.engine.SystemAudioTrack;
import dev.sertas.engine.WebRtcEngine;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.layout.FlowPane;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Связывает {@link MeshCoordinator} с JavaFX-UI. Колбэки меша приходят на
 * нативных/WS-потоках и маршалятся на FX-поток через {@link Platform#runLater}.
 */
public final class CallController implements MeshListener {

    private final ObservableList<ParticipantModel> participants = FXCollections.observableArrayList();
    private final FlowPane videoPane = new FlowPane(12, 12);
    private final Map<String, ParticipantModel> byId = new HashMap<>();   // только FX-поток
    private final Map<String, VideoTile> tiles = new HashMap<>();         // только FX-поток

    private WebRtcEngine engine;
    private MeshCoordinator mesh;
    private AudioTrack mic;
    private ScreenCaptureSource screen;
    private SystemAudioTrack screenAudio;
    private ParticipantModel self;
    private boolean micMuted = false;
    private boolean sharing = false;
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

        mic = engine.createMicTrack();
        mesh.addLocalTrack(mic);

        // Экранный трек согласуем сразу (но не захватываем) — чтобы потом начать
        // демонстрацию без повторной переговорки.
        screen = new ScreenCaptureSource();
        VideoTrack screenTrack = engine.createVideoTrack("screen", screen.source());
        mesh.addLocalTrack(screenTrack);

        // Трек звука демонстрации согласуем сразу (как видео-экран), включаем позже.
        screenAudio = new SystemAudioTrack(engine);
        mesh.addLocalTrack(screenAudio.track());

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

    /** Начать демонстрацию экрана (выбирается основной экран). */
    public void startScreenShare() {
        try {
            List<dev.onvoid.webrtc.media.video.desktop.DesktopSource> screens = ScreenCaptureSource.screens();
            if (screens.isEmpty()) {
                onError(new IllegalStateException("нет доступных экранов (проверьте разрешение)"));
                return;
            }
            screen.select(screens.get(0).id, ScreenCaptureSource.Quality.BALANCED);
            screen.start();
            sharing = true;
        } catch (RuntimeException e) {
            onError(e);
        }
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
        if (screenAudio != null) {
            // Фаза A: временный источник-синус 440Гц. Фаза B заменит на
            // нативный ScreenCaptureKit (MacSystemAudioCapture).
            screenAudio.start(new FakeSystemAudioProvider(440));
            screenAudioOn = true;
        }
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
        if (mesh != null) {
            mesh.stop();
            mesh = null;
        }
        if (engine != null) {
            engine.dispose();
            engine = null;
        }
        mic = null;
        screen = null;
        Platform.runLater(() -> {
            tiles.values().forEach(VideoTile::dispose);
            tiles.clear();
            videoPane.getChildren().clear();
            participants.clear();
            byId.clear();
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
    public void onRemoteTrack(String peerId, RTCRtpTransceiver transceiver) {
        Platform.runLater(() -> {
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            if (track == null || !MediaStreamTrack.VIDEO_TRACK_KIND.equals(track.getKind())) {
                return; // аудио проигрывается само, плитка не нужна
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
