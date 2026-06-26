// Нативный захват экрана (видео) на Windows через DXGI Desktop Duplication.
// Pull-модель как на macOS: выделенный поток тянет кадры рабочего стола (BGRA),
// держит ПОСЛЕДНИЙ кадр; Java через nativeRead копирует его и конвертит BGRA->I420.
// Чистый Win32/COM (d3d11 + dxgi), без WinRT — проще собирать в CI (MSVC).
//
// JNI: dev.sertas.engine.WinScreenVideoCapture {nativeStart,nativeRead,nativeStop}.

#include <jni.h>
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")

namespace {

template <class T>
void safeRelease(T*& p) {
    if (p) {
        p->Release();
        p = nullptr;
    }
}

struct Capturer {
    // Запрошенный максимум (пресет качества); кадр уменьшается целочисленно до него.
    int reqW = 1920;
    int reqH = 1080;

    ID3D11Device* device = nullptr;
    ID3D11DeviceContext* ctx = nullptr;
    IDXGIOutputDuplication* dupl = nullptr;
    ID3D11Texture2D* staging = nullptr;
    int srcW = 0;
    int srcH = 0;

    std::thread worker;
    std::atomic<bool> running{false};

    std::mutex mtx;            // защищает frame/frameW/frameH/seq
    std::vector<uint8_t> frame; // последний кадр BGRA, плотно упакован outW*outH*4
    int frameW = 0;
    int frameH = 0;
    long long seq = 0;
    long long lastRead = 0;

    bool initDup() {
        D3D_FEATURE_LEVEL fl;
        HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0,
                                       nullptr, 0, D3D11_SDK_VERSION, &device, &fl, &ctx);
        if (FAILED(hr)) {
            return false;
        }
        IDXGIDevice* dxgiDev = nullptr;
        if (FAILED(device->QueryInterface(__uuidof(IDXGIDevice), (void**)&dxgiDev))) {
            return false;
        }
        IDXGIAdapter* adapter = nullptr;
        hr = dxgiDev->GetAdapter(&adapter);
        safeRelease(dxgiDev);
        if (FAILED(hr)) {
            return false;
        }
        IDXGIOutput* output = nullptr;
        hr = adapter->EnumOutputs(0, &output); // основной выход (дисплей 0)
        safeRelease(adapter);
        if (FAILED(hr)) {
            return false;
        }
        IDXGIOutput1* output1 = nullptr;
        hr = output->QueryInterface(__uuidof(IDXGIOutput1), (void**)&output1);
        safeRelease(output);
        if (FAILED(hr)) {
            return false;
        }
        hr = output1->DuplicateOutput(device, &dupl);
        safeRelease(output1);
        if (FAILED(hr)) {
            return false;
        }
        DXGI_OUTDUPL_DESC desc;
        dupl->GetDesc(&desc);
        srcW = (int)desc.ModeDesc.Width;
        srcH = (int)desc.ModeDesc.Height;

        D3D11_TEXTURE2D_DESC td = {};
        td.Width = srcW;
        td.Height = srcH;
        td.MipLevels = 1;
        td.ArraySize = 1;
        td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        td.SampleDesc.Count = 1;
        td.Usage = D3D11_USAGE_STAGING;
        td.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        if (FAILED(device->CreateTexture2D(&td, nullptr, &staging))) {
            return false;
        }
        return true;
    }

    void releaseDup() {
        safeRelease(staging);
        safeRelease(dupl);
        safeRelease(ctx);
        safeRelease(device);
    }

    // Целочисленный коэффициент уменьшения, чтобы кадр влез в reqW x reqH.
    int factor() const {
        int f = 1;
        while ((srcW / f) > reqW || (srcH / f) > reqH) {
            f++;
        }
        return f;
    }

    void copyFrame(const uint8_t* src, int rowPitch) {
        int f = factor();
        int outW = (srcW / f) & ~1;
        int outH = (srcH / f) & ~1;
        if (outW <= 0 || outH <= 0) {
            return;
        }
        std::lock_guard<std::mutex> lk(mtx);
        frame.resize((size_t)outW * outH * 4);
        uint8_t* dst = frame.data();
        for (int y = 0; y < outH; y++) {
            const uint8_t* srow = src + (size_t)(y * f) * rowPitch;
            uint8_t* drow = dst + (size_t)y * outW * 4;
            if (f == 1) {
                memcpy(drow, srow, (size_t)outW * 4);
            } else {
                for (int x = 0; x < outW; x++) {
                    const uint8_t* sp = srow + (size_t)(x * f) * 4;
                    uint8_t* dp = drow + (size_t)x * 4;
                    dp[0] = sp[0];
                    dp[1] = sp[1];
                    dp[2] = sp[2];
                    dp[3] = sp[3];
                }
            }
        }
        frameW = outW;
        frameH = outH;
        seq++;
    }

    void loop() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        if (!initDup()) {
            running = false;
            CoUninitialize();
            return;
        }
        while (running) {
            DXGI_OUTDUPL_FRAME_INFO info;
            IDXGIResource* res = nullptr;
            HRESULT hr = dupl->AcquireNextFrame(100, &info, &res);
            if (hr == DXGI_ERROR_WAIT_TIMEOUT) {
                continue; // нет нового кадра (экран не менялся)
            }
            if (FAILED(hr)) {
                // потеря доступа (смена режима/Ctrl+Alt+Del/полный экран) — пересоздать
                releaseDup();
                if (!initDup()) {
                    Sleep(50);
                }
                continue;
            }
            ID3D11Texture2D* tex = nullptr;
            if (SUCCEEDED(res->QueryInterface(__uuidof(ID3D11Texture2D), (void**)&tex))) {
                ctx->CopyResource(staging, tex);
                safeRelease(tex);
                D3D11_MAPPED_SUBRESOURCE map;
                if (SUCCEEDED(ctx->Map(staging, 0, D3D11_MAP_READ, 0, &map))) {
                    copyFrame((const uint8_t*)map.pData, (int)map.RowPitch);
                    ctx->Unmap(staging, 0);
                }
            }
            safeRelease(res);
            dupl->ReleaseFrame();
        }
        releaseDup();
        CoUninitialize();
    }

    bool start() {
        running = true;
        worker = std::thread(&Capturer::loop, this);
        // подождать, пока поток инициализирует дублирование (или упадёт)
        for (int i = 0; i < 50 && running; i++) {
            {
                std::lock_guard<std::mutex> lk(mtx);
                if (seq > 0 || dupl != nullptr) {
                    return true;
                }
            }
            Sleep(20);
        }
        return running;
    }

    void stop() {
        running = false;
        if (worker.joinable()) {
            worker.join();
        }
    }

    // Скопировать последний кадр в out (если новее прочитанного). (байт, w, h).
    int take(std::vector<uint8_t>& out, int& w, int& h) {
        std::lock_guard<std::mutex> lk(mtx);
        if (seq == lastRead || frame.empty()) {
            return 0;
        }
        out = frame;
        w = frameW;
        h = frameH;
        lastRead = seq;
        return (int)out.size();
    }
};

std::mutex g_lock;
Capturer* g_cap = nullptr;

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_dev_sertas_engine_WinScreenVideoCapture_nativeStart(JNIEnv*, jclass, jint reqW, jint reqH, jint /*fps*/) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (g_cap != nullptr) {
        return 1;
    }
    Capturer* c = new Capturer();
    c->reqW = reqW > 0 ? reqW : 1920;
    c->reqH = reqH > 0 ? reqH : 1080;
    if (c->start()) {
        g_cap = c;
        return 1;
    }
    c->stop();
    delete c;
    return 0;
}

JNIEXPORT jint JNICALL
Java_dev_sertas_engine_WinScreenVideoCapture_nativeRead(JNIEnv* env, jclass, jbyteArray bgra, jint maxBytes, jintArray dims) {
    Capturer* c = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_lock);
        c = g_cap;
    }
    if (c == nullptr || maxBytes <= 0) {
        return 0;
    }
    std::vector<uint8_t> buf;
    int w = 0;
    int h = 0;
    int n = c->take(buf, w, h);
    if (n <= 0) {
        return 0;
    }
    if (n > maxBytes) {
        n = maxBytes;
    }
    env->SetByteArrayRegion(bgra, 0, n, (const jbyte*)buf.data());
    jint wh[2] = {(jint)w, (jint)h};
    env->SetIntArrayRegion(dims, 0, 2, wh);
    return n;
}

JNIEXPORT void JNICALL
Java_dev_sertas_engine_WinScreenVideoCapture_nativeStop(JNIEnv*, jclass) {
    Capturer* c = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_lock);
        c = g_cap;
        g_cap = nullptr;
    }
    if (c != nullptr) {
        c->stop();
        delete c;
    }
}

} // extern "C"
