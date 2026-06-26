package dev.sertas.engine;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Соединение для звука демонстрации: отдельная {@link PeerSession} на ОТДЕЛЬНОМ
 * (обычно headless) движке, чьи offer/answer/ICE туннелируются через УЖЕ
 * существующий control data-channel главного соединения — без правок
 * сигналинг-сервера/протокола.
 *
 * <p>Зачем отдельный движок: headless-движок без реального ADM, поэтому
 * {@code pushAudio} трека screen-audio не конфликтует с реальным ADM голоса
 * (диагноз — гонка в нативном {@code audio_send_stream}). Голос остаётся на
 * реальном ADM с AEC; звук демо едет своим соединением.
 */
public final class ScreenAudioConnection {

    private final PeerSession session;
    private final RTCDataChannel channel;
    private final AtomicBoolean offered = new AtomicBoolean();

    /**
     * @param audioEngine        отдельный движок (headless) для звука демо
     * @param channel            control data-channel главного соединения (транспорт сигналинга)
     * @param initiator          инициатор offer (та же сторона, что и в главном меше)
     * @param localTrack         локальный screen-audio трек (null у чистого зрителя)
     * @param onRemoteTrack      удалённый screen-audio трек прибыл (для воспроизведения)
     * @param localSdpTransform  SDP-munging (музыкальный профиль Opus)
     */
    public ScreenAudioConnection(WebRtcEngine audioEngine, RTCDataChannel channel, boolean initiator,
                                 MediaStreamTrack localTrack,
                                 Consumer<RTCRtpTransceiver> onRemoteTrack,
                                 UnaryOperator<String> localSdpTransform) {
        this.channel = channel;
        System.err.println("[demo] ScreenAudioConnection создан (initiator=" + initiator
                + ", localTrack=" + (localTrack != null) + ")");
        this.session = new PeerSession(audioEngine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                System.err.println("[demo] saConn → " + (d.sdpType == RTCSdpType.OFFER ? "offer" : "answer"));
                send(d.sdpType == RTCSdpType.OFFER ? 'O' : 'A', d.sdp);
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                send('I', c.sdpMid + "" + c.sdpMLineIndex + "" + c.sdp);
            }

            @Override
            public void onConnectionState(dev.onvoid.webrtc.RTCPeerConnectionState state) {
                System.err.println("[demo] saConn state=" + state);
            }

            @Override
            public void onTrack(RTCRtpTransceiver t) {
                System.err.println("[demo] saConn ← удалённый screen-audio трек прибыл");
                onRemoteTrack.accept(t);
            }

            @Override
            public void onError(Throwable e) {
                System.err.println("[demo] saConn error: " + e);
            }
        }, null, localSdpTransform);

        if (localTrack != null) {
            session.addTrack(localTrack);
        }

        channel.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {}

            @Override
            public void onStateChange() {
                maybeOffer(initiator);
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                handle(buffer);
            }
        });
        maybeOffer(initiator); // вдруг канал уже открыт
    }

    private void maybeOffer(boolean initiator) {
        if (initiator && channel.getState() == RTCDataChannelState.OPEN && offered.compareAndSet(false, true)) {
            System.err.println("[demo] control-канал OPEN → создаю offer звука демо");
            session.createOffer();
        }
    }

    private void send(char kind, String payload) {
        String msg = kind + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8)), false));
        } catch (Exception ignored) {
            // канал закрылся — соединение пересоберётся при следующем control-канале
        }
    }

    private void handle(RTCDataChannelBuffer buffer) {
        byte[] b = new byte[buffer.data.remaining()];
        buffer.data.get(b);
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.isEmpty()) {
            return;
        }
        char kind = s.charAt(0);
        String payload = new String(Base64.getDecoder().decode(s.substring(1)), StandardCharsets.UTF_8);
        if (kind == 'O' || kind == 'A') {
            System.err.println("[demo] saConn ← " + (kind == 'O' ? "offer" : "answer"));
        }
        switch (kind) {
            case 'O' -> session.onRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, payload));
            case 'A' -> session.onRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, payload));
            case 'I' -> {
                String[] p = payload.split("", 3);
                session.onRemoteIceCandidate(new RTCIceCandidate(p[0], Integer.parseInt(p[1]), p[2]));
            }
            default -> { /* чужое сообщение на канале — игнор */ }
        }
    }

    public void close() {
        session.close();
    }
}
