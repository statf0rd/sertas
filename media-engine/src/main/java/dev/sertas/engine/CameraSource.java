package dev.sertas.engine;

import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;

import java.util.List;

/**
 * Захват с камеры через встроенный {@link VideoDeviceSource}. Реальный захват
 * требует физической камеры и разрешения (TCC на macOS) — проверяется вручную.
 */
public final class CameraSource {

    private final VideoDeviceSource source = new VideoDeviceSource();

    public CameraSource(VideoDevice device) {
        source.setVideoCaptureDevice(device);
    }

    public CameraSource(VideoDevice device, VideoCaptureCapability capability) {
        source.setVideoCaptureDevice(device);
        source.setVideoCaptureCapability(capability);
    }

    /** Доступные камеры. */
    public static List<VideoDevice> cameras() {
        return MediaDevices.getVideoCaptureDevices();
    }

    /** Поддерживаемые режимы (разрешение/FPS) камеры. */
    public static List<VideoCaptureCapability> capabilities(VideoDevice device) {
        return MediaDevices.getVideoCaptureCapabilities(device);
    }

    public VideoDeviceSource source() {
        return source;
    }

    public void start() {
        source.start();
    }

    public void stop() {
        source.stop();
    }

    public void dispose() {
        source.dispose();
    }
}
