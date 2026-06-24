package dev.sertas.engine;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Один {@link RTCPeerConnection} к одному удалённому пиру в P2P-меше. Прячет
 * асинхронный танец offer/answer/ICE за простым API и буферизует входящие
 * ICE-кандидаты до установки remote-description (иначе libwebrtc их отвергает).
 *
 * <p>Все исходящие сигналы (локальные SDP и ICE) и смены состояния отдаются
 * через {@link Signals}. Транспортом сигналов (WebSocket) занимается вызывающий.
 */
public final class PeerSession {

    /** Колбэки наружу. Вызываются на нативных потоках WebRTC. */
    public interface Signals {
        void onLocalDescription(RTCSessionDescription description);

        void onLocalIceCandidate(RTCIceCandidate candidate);

        default void onConnectionState(RTCPeerConnectionState state) {}

        /** Удалённый пир открыл data-channel. */
        default void onDataChannel(RTCDataChannel channel) {}
    }

    private final RTCPeerConnection pc;
    private final Signals signals;

    /** Хук преобразования локального SDP перед отправкой (SDP-munging). По умолчанию — без изменений. */
    private final UnaryOperator<String> localSdpTransform;

    private final Object lock = new Object();
    private final List<RTCIceCandidate> pendingRemote = new ArrayList<>();
    private boolean remoteDescriptionSet = false;

    public PeerSession(WebRtcEngine engine, Signals signals) {
        this(engine, signals, UnaryOperator.identity());
    }

    public PeerSession(WebRtcEngine engine, Signals signals, UnaryOperator<String> localSdpTransform) {
        this.signals = signals;
        this.localSdpTransform = localSdpTransform;
        this.pc = engine.createPeerConnection(new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                signals.onLocalIceCandidate(candidate);
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                signals.onConnectionState(state);
            }

            @Override
            public void onDataChannel(RTCDataChannel channel) {
                signals.onDataChannel(channel);
            }
        });
    }

    public RTCPeerConnection peerConnection() {
        return pc;
    }

    public RTCDataChannel createDataChannel(String label) {
        return pc.createDataChannel(label, new RTCDataChannelInit());
    }

    /** Инициировать соединение: создать offer, установить локально, отдать наружу. */
    public void createOffer() {
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                applyAndSignalLocal(description);
            }

            @Override
            public void onFailure(String error) {
                throw new IllegalStateException("createOffer failed: " + error);
            }
        });
    }

    /** Принять удалённый SDP. На offer автоматически отвечаем answer'ом. */
    public void onRemoteDescription(RTCSessionDescription description) {
        pc.setRemoteDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                flushPendingCandidates();
                if (description.sdpType == RTCSdpType.OFFER) {
                    createAnswer();
                }
            }

            @Override
            public void onFailure(String error) {
                throw new IllegalStateException("setRemoteDescription failed: " + error);
            }
        });
    }

    /** Принять удалённый ICE-кандидат (с буферизацией до remote-description). */
    public void onRemoteIceCandidate(RTCIceCandidate candidate) {
        synchronized (lock) {
            if (!remoteDescriptionSet) {
                pendingRemote.add(candidate);
                return;
            }
        }
        pc.addIceCandidate(candidate);
    }

    public void close() {
        pc.close();
    }

    private void createAnswer() {
        pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                applyAndSignalLocal(description);
            }

            @Override
            public void onFailure(String error) {
                throw new IllegalStateException("createAnswer failed: " + error);
            }
        });
    }

    private void applyAndSignalLocal(RTCSessionDescription description) {
        String munged = localSdpTransform.apply(description.sdp);
        RTCSessionDescription local = munged.equals(description.sdp)
                ? description
                : new RTCSessionDescription(description.sdpType, munged);
        pc.setLocalDescription(local, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                signals.onLocalDescription(local);
            }

            @Override
            public void onFailure(String error) {
                throw new IllegalStateException("setLocalDescription failed: " + error);
            }
        });
    }

    private void flushPendingCandidates() {
        List<RTCIceCandidate> toAdd;
        synchronized (lock) {
            remoteDescriptionSet = true;
            toAdd = new ArrayList<>(pendingRemote);
            pendingRemote.clear();
        }
        for (RTCIceCandidate c : toAdd) {
            pc.addIceCandidate(c);
        }
    }
}
