package dev.sertas.engine;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStreamTrack;

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

        /** Удалённый трек (аудио/видео) согласован — для рендера/воспроизведения. */
        default void onTrack(RTCRtpTransceiver transceiver) {}

        /** Ошибка в асинхронном шаге. Никогда не бросается в нативный код. */
        default void onError(Throwable error) {}
    }

    private final RTCPeerConnection pc;
    private final Signals signals;

    /** Преобразование локального SDP перед setLocalDescription (SDP-munging). */
    private final UnaryOperator<String> localSdpTransform;

    private final Object lock = new Object();
    private final List<RTCIceCandidate> pendingRemote = new ArrayList<>();
    private boolean remoteDescriptionSet = false;

    public PeerSession(WebRtcEngine engine, Signals signals) {
        this(engine, signals, (List<RTCIceServer>) null);
    }

    /** @param iceServers ICE-серверы от сервера; null/пусто → дефолт движка. */
    public PeerSession(WebRtcEngine engine, Signals signals, List<RTCIceServer> iceServers) {
        this(engine, signals, iceServers, UnaryOperator.identity());
    }

    /**
     * @param iceServers        ICE-серверы от сервера; null/пусто → дефолт движка.
     * @param localSdpTransform преобразование локального SDP перед
     *                          {@code setLocalDescription} (SDP-munging); identity — без изменений.
     */
    public PeerSession(WebRtcEngine engine, Signals signals, List<RTCIceServer> iceServers,
                       UnaryOperator<String> localSdpTransform) {
        this.signals = signals;
        this.localSdpTransform = localSdpTransform;
        this.pc = engine.createPeerConnection(new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                safe(() -> signals.onLocalIceCandidate(candidate));
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                safe(() -> signals.onConnectionState(state));
            }

            @Override
            public void onDataChannel(RTCDataChannel channel) {
                safe(() -> signals.onDataChannel(channel));
            }

            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {
                safe(() -> signals.onTrack(transceiver));
            }
        }, iceServers);
    }

    public RTCPeerConnection peerConnection() {
        return pc;
    }

    public RTCDataChannel createDataChannel(String label) {
        return pc.createDataChannel(label, new RTCDataChannelInit());
    }

    /** Локальный медиа-трек (микрофон/камера/экран) в этот peer-connection. */
    public void addTrack(MediaStreamTrack track) {
        RTCRtpSender sender = pc.addTrack(track, List.of(STREAM_ID));
        if (MediaStreamTrack.VIDEO_TRACK_KIND.equals(track.getKind())) {
            try {
                RTCRtpSendParameters params = sender.getParameters();
                if (params.encodings != null && !params.encodings.isEmpty()) {
                    for (RTCRtpEncodingParameters enc : params.encodings) {
                        enc.maxBitrate = VIDEO_MAX_BITRATE;
                    }
                    sender.setParameters(params);
                }
            } catch (RuntimeException ignored) {
                // параметры можно будет применить после переговорки
            }
        }
    }

    private static final String STREAM_ID = "sertas";
    private static final int VIDEO_MAX_BITRATE = 5_000_000; // 5 Мбит/с для чёткой демонстрации

    /** Инициировать соединение: создать offer, установить локально, отдать наружу. */
    public void createOffer() {
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                safe(() -> applyAndSignalLocal(description));
            }

            @Override
            public void onFailure(String error) {
                signals.onError(new IllegalStateException("createOffer failed: " + error));
            }
        });
    }

    /** Принять удалённый SDP. На offer автоматически отвечаем answer'ом. */
    public void onRemoteDescription(RTCSessionDescription description) {
        pc.setRemoteDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                safe(() -> {
                    flushPendingCandidates();
                    if (description.sdpType == RTCSdpType.OFFER) {
                        createAnswer();
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                signals.onError(new IllegalStateException("setRemoteDescription failed: " + error));
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
        safe(() -> pc.addIceCandidate(candidate));
    }

    public void close() {
        // Метка времени освобождения нативного PeerConnection (и его remote-треков).
        // Если t здесь МЕНЬШЕ, чем t у «removeSink CALLING» в VideoTile — это и есть
        // use-after-free (sink снимается с уже освобождённого трека).
        System.err.println("[life] PeerSession.close thread=" + Thread.currentThread().getName()
                + " t=" + System.nanoTime());
        pc.close();
        System.err.println("[life] PeerSession.close DONE t=" + System.nanoTime());
    }

    private void createAnswer() {
        pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                safe(() -> applyAndSignalLocal(description));
            }

            @Override
            public void onFailure(String error) {
                signals.onError(new IllegalStateException("createAnswer failed: " + error));
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
                safe(() -> signals.onLocalDescription(local));
            }

            @Override
            public void onFailure(String error) {
                signals.onError(new IllegalStateException("setLocalDescription failed: " + error));
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

    /** Выполнить шаг, не давая исключению уйти в нативный код (иначе краш JVM). */
    private void safe(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            signals.onError(e);
        }
    }
}
