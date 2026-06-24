package dev.sertas.engine;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiver;

/** События меша наружу (в UI). Вызываются на нативных потоках WebRTC / WS. */
public interface MeshListener {

    default void onPeerJoined(String peerId, String name) {}

    default void onPeerLeft(String peerId) {}

    default void onPeerState(String peerId, RTCPeerConnectionState state) {}

    /** Удалённый трек (аудио/видео) от пира — для подписки на рендер/воспроизведение. */
    default void onRemoteTrack(String peerId, RTCRtpTransceiver transceiver) {}

    /** Управляющий data-channel с пиром открыт. */
    default void onControlChannel(String peerId, RTCDataChannel channel) {}

    default void onError(Throwable error) {}
}
