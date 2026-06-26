// Нативный захват системного звука на Windows через WASAPI loopback (.dll + JNI).
// Pull-модель: фоновый поток тянет mix render-устройства (AUDCLNT_STREAMFLAGS_LOOPBACK)
// в кольцевой буфер планарных L/R Float32; Java тянет через nativeRead. Симметрично
// macOS-дилибу (dev.sertas.engine.WinSystemAudioCapture).
//
// Сборка: scripts/build-windows-audio-dll.bat (MSVC). Требует Windows SDK.

#include <jni.h>
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <mmreg.h>
#include <ksmedia.h>

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <vector>
#include <deque>

#pragma comment(lib, "ole32.lib")

namespace {

const CLSID kCLSID_MMDeviceEnumerator = __uuidof(MMDeviceEnumerator);
const IID kIID_IMMDeviceEnumerator = __uuidof(IMMDeviceEnumerator);
const IID kIID_IAudioClient = __uuidof(IAudioClient);
const IID kIID_IAudioCaptureClient = __uuidof(IAudioCaptureClient);

// Кольцевой буфер планарных сэмплов (L/R), под мьютексом.
class Ring {
public:
    void push(const float* l, const float* r, size_t n) {
        std::lock_guard<std::mutex> lock(m_);
        const size_t cap = 48000 * 2; // ~2с страховка
        if (left_.size() + n > cap) {
            size_t drop = (left_.size() + n) - cap;
            if (drop > left_.size()) drop = left_.size();
            left_.erase(left_.begin(), left_.begin() + drop);
            right_.erase(right_.begin(), right_.begin() + drop);
        }
        left_.insert(left_.end(), l, l + n);
        right_.insert(right_.end(), r, r + n);
    }
    size_t drain(float* outL, float* outR, size_t maxFrames) {
        std::lock_guard<std::mutex> lock(m_);
        size_t n = left_.size() < maxFrames ? left_.size() : maxFrames;
        for (size_t i = 0; i < n; ++i) { outL[i] = left_[i]; outR[i] = right_[i]; }
        left_.erase(left_.begin(), left_.begin() + n);
        right_.erase(right_.begin(), right_.begin() + n);
        return n;
    }
    void clear() {
        std::lock_guard<std::mutex> lock(m_);
        left_.clear(); right_.clear();
    }
private:
    std::mutex m_;
    std::deque<float> left_, right_;
};

struct Capture {
    std::thread thread;
    std::atomic<bool> running{false};
    Ring ring;
    int sampleRate{48000};
    // handshake старта
    std::mutex startMx;
    std::condition_variable startCv;
    int startResult{-1}; // -1 ожидание, 1 ок, 0 ошибка
};

Capture* g = nullptr;
std::mutex g_mutex;

// Один кадр WASAPI (interleaved, формат mix) → планарные L/R float, первые 2 канала.
void appendFrames(Ring& ring, const BYTE* data, UINT32 frames,
                  WORD channels, WORD bitsPerSample, bool isFloat) {
    std::vector<float> l(frames), r(frames);
    const size_t stride = channels;
    if (isFloat && bitsPerSample == 32) {
        const float* f = reinterpret_cast<const float*>(data);
        for (UINT32 i = 0; i < frames; ++i) {
            l[i] = f[i * stride];
            r[i] = channels > 1 ? f[i * stride + 1] : f[i * stride];
        }
    } else if (!isFloat && bitsPerSample == 16) {
        const int16_t* s = reinterpret_cast<const int16_t*>(data);
        for (UINT32 i = 0; i < frames; ++i) {
            l[i] = s[i * stride] / 32768.0f;
            r[i] = channels > 1 ? s[i * stride + 1] / 32768.0f : s[i * stride] / 32768.0f;
        }
    } else {
        return; // неизвестный формат — пропускаем
    }
    ring.push(l.data(), r.data(), frames);
}

void captureLoop(Capture* cap) {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool comInited = SUCCEEDED(hr);

    IMMDeviceEnumerator* enumerator = nullptr;
    IMMDevice* device = nullptr;
    IAudioClient* client = nullptr;
    IAudioCaptureClient* capture = nullptr;
    WAVEFORMATEX* wfx = nullptr;
    bool ok = false;

    do {
        if (FAILED(CoCreateInstance(kCLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                    kIID_IMMDeviceEnumerator, (void**)&enumerator))) break;
        if (FAILED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device))) break;
        if (FAILED(device->Activate(kIID_IAudioClient, CLSCTX_ALL, nullptr, (void**)&client))) break;
        if (FAILED(client->GetMixFormat(&wfx))) break;

        cap->sampleRate = (int)wfx->nSamplesPerSec;

        REFERENCE_TIME bufDuration = 2 * 10000000LL; // 2с (в 100нс-единицах)
        if (FAILED(client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                      AUDCLNT_STREAMFLAGS_LOOPBACK,
                                      bufDuration, 0, wfx, nullptr))) break;
        if (FAILED(client->GetService(kIID_IAudioCaptureClient, (void**)&capture))) break;
        if (FAILED(client->Start())) break;
        ok = true;
    } while (false);

    // Определяем формат mix (Float32 или PCM16).
    WORD channels = wfx ? wfx->nChannels : 2;
    WORD bits = wfx ? wfx->wBitsPerSample : 32;
    bool isFloat = false;
    if (wfx) {
        if (wfx->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) {
            isFloat = true;
        } else if (wfx->wFormatTag == WAVE_FORMAT_EXTENSIBLE) {
            WAVEFORMATEXTENSIBLE* ext = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(wfx);
            isFloat = (ext->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
        }
    }

    {
        std::lock_guard<std::mutex> lk(cap->startMx);
        cap->startResult = ok ? 1 : 0;
    }
    cap->startCv.notify_all();

    if (ok) {
        while (cap->running.load()) {
            UINT32 packet = 0;
            if (FAILED(capture->GetNextPacketSize(&packet))) break;
            while (packet > 0) {
                BYTE* data = nullptr;
                UINT32 frames = 0;
                DWORD flags = 0;
                if (FAILED(capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr))) break;
                if (frames > 0 && !(flags & AUDCLNT_BUFFERFLAGS_SILENT) && data) {
                    appendFrames(cap->ring, data, frames, channels, bits, isFloat);
                }
                capture->ReleaseBuffer(frames);
                if (FAILED(capture->GetNextPacketSize(&packet))) { packet = 0; break; }
            }
            Sleep(5);
        }
        client->Stop();
    }

    if (capture) capture->Release();
    if (client) client->Release();
    if (device) device->Release();
    if (enumerator) enumerator->Release();
    if (wfx) CoTaskMemFree(wfx);
    if (comInited) CoUninitialize();
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_dev_sertas_engine_WinSystemAudioCapture_nativeStart(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g) return 1;
    Capture* cap = new Capture();
    cap->running.store(true);
    cap->thread = std::thread(captureLoop, cap);

    std::unique_lock<std::mutex> sl(cap->startMx);
    cap->startCv.wait(sl, [&] { return cap->startResult != -1; });
    int res = cap->startResult;
    sl.unlock();

    if (res != 1) {
        cap->running.store(false);
        if (cap->thread.joinable()) cap->thread.join();
        delete cap;
        return 0;
    }
    g = cap;
    return 1;
}

JNIEXPORT jint JNICALL
Java_dev_sertas_engine_WinSystemAudioCapture_nativeSampleRate(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_mutex);
    return g ? g->sampleRate : 48000;
}

JNIEXPORT jint JNICALL
Java_dev_sertas_engine_WinSystemAudioCapture_nativeRead(
        JNIEnv* env, jclass, jfloatArray leftArr, jfloatArray rightArr, jint maxFrames) {
    Capture* cap;
    { std::lock_guard<std::mutex> lk(g_mutex); cap = g; }
    if (!cap || maxFrames <= 0) return 0;

    std::vector<float> l(maxFrames), r(maxFrames);
    size_t n = cap->ring.drain(l.data(), r.data(), (size_t)maxFrames);
    if (n == 0) return 0;
    env->SetFloatArrayRegion(leftArr, 0, (jsize)n, l.data());
    env->SetFloatArrayRegion(rightArr, 0, (jsize)n, r.data());
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_dev_sertas_engine_WinSystemAudioCapture_nativeStop(JNIEnv*, jclass) {
    Capture* cap;
    { std::lock_guard<std::mutex> lk(g_mutex); cap = g; g = nullptr; }
    if (!cap) return;
    cap->running.store(false);
    if (cap->thread.joinable()) cap->thread.join();
    delete cap;
}

} // extern "C"
