package dev.sertas.app;

import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.sertas.app.ui.ParticipantModel;
import dev.sertas.engine.MeshCoordinator;
import dev.sertas.engine.MeshListener;
import dev.sertas.engine.WebRtcEngine;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.HashMap;
import java.util.Map;

/**
 * Связывает {@link MeshCoordinator} с JavaFX-UI. Все колбэки меша приходят на
 * нативных/WS-потоках и маршалятся на FX-поток через {@link Platform#runLater}.
 */
public final class CallController implements MeshListener {

    private final ObservableList<ParticipantModel> participants = FXCollections.observableArrayList();
    private final Map<String, ParticipantModel> byId = new HashMap<>(); // только FX-поток

    private WebRtcEngine engine;
    private MeshCoordinator mesh;
    private AudioTrack mic;
    private boolean micMuted = false;

    public ObservableList<ParticipantModel> participants() {
        return participants;
    }

    /** Создать движок, микрофонный трек и войти в комнату. */
    public void join(String url, String room, String name) {
        engine = new WebRtcEngine();
        mesh = new MeshCoordinator(engine, this);
        mic = engine.createMicTrack();
        mesh.addLocalTrack(mic);
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

    public void leave() {
        if (mesh != null) {
            mesh.stop();
            mesh = null;
        }
        if (engine != null) {
            engine.dispose();
            engine = null;
        }
        mic = null;
        Platform.runLater(() -> {
            participants.clear();
            byId.clear();
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
