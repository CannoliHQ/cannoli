#include "native_audio.h"
#include <oboe/Oboe.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <android/log.h>

#define LOG_TAG "NativeAudio"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::string sDiagnostics;

static const char* audioApiName(oboe::AudioApi api) {
    switch (api) {
        case oboe::AudioApi::Unspecified: return "Unspecified";
        case oboe::AudioApi::OpenSLES:    return "OpenSLES";
        case oboe::AudioApi::AAudio:      return "AAudio";
        default:                          return "?";
    }
}

static const char* sharingModeName(oboe::SharingMode m) {
    switch (m) {
        case oboe::SharingMode::Exclusive: return "Exclusive";
        case oboe::SharingMode::Shared:    return "Shared";
        default:                           return "?";
    }
}

static const char* perfModeName(oboe::PerformanceMode m) {
    switch (m) {
        case oboe::PerformanceMode::None:          return "None";
        case oboe::PerformanceMode::PowerSaving:   return "PowerSaving";
        case oboe::PerformanceMode::LowLatency:    return "LowLatency";
        default:                                   return "?";
    }
}

// SPSC ring buffer for stereo int16 samples
static constexpr int32_t RING_CAPACITY = 16384;
static int16_t sRingBuf[RING_CAPACITY * 2]; // stereo
static std::atomic<int32_t> sWritePos{0};
static std::atomic<int32_t> sReadPos{0};

static int32_t ringAvailable() {
    int32_t w = sWritePos.load(std::memory_order_acquire);
    int32_t r = sReadPos.load(std::memory_order_acquire);
    return (w - r + RING_CAPACITY) % RING_CAPACITY;
}

static int32_t ringFree() {
    return RING_CAPACITY - 1 - ringAvailable();
}

static void ringWrite(const int16_t* data, int32_t frames) {
    int32_t w = sWritePos.load(std::memory_order_relaxed);
    int32_t free = ringFree();
    if (frames > free) frames = free;

    for (int32_t i = 0; i < frames; i++) {
        int32_t pos = (w + i) % RING_CAPACITY;
        sRingBuf[pos * 2]     = data[i * 2];
        sRingBuf[pos * 2 + 1] = data[i * 2 + 1];
    }
    sWritePos.store((w + frames) % RING_CAPACITY, std::memory_order_release);
}

static int32_t ringRead(int16_t* out, int32_t frames) {
    int32_t r = sReadPos.load(std::memory_order_relaxed);
    int32_t avail = ringAvailable();
    if (frames > avail) frames = avail;

    for (int32_t i = 0; i < frames; i++) {
        int32_t pos = (r + i) % RING_CAPACITY;
        out[i * 2]     = sRingBuf[pos * 2];
        out[i * 2 + 1] = sRingBuf[pos * 2 + 1];
    }
    sReadPos.store((r + frames) % RING_CAPACITY, std::memory_order_release);
    return frames;
}

// Oboe callback — runs on high-priority audio thread
class AudioCallback : public oboe::AudioStreamDataCallback {
public:
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        auto* out = static_cast<int16_t*>(audioData);
        int32_t read = ringRead(out, numFrames);
        if (read < numFrames) {
            memset(out + read * 2, 0, (numFrames - read) * 2 * sizeof(int16_t));
        }
        return oboe::DataCallbackResult::Continue;
    }
};

static AudioCallback sCallback;
static std::shared_ptr<oboe::AudioStream> sStream;
static std::atomic<bool> sMuted{false};

void nativeAudioInit(int32_t sampleRate) {
    nativeAudioStop();

    sWritePos.store(0, std::memory_order_relaxed);
    sReadPos.store(0, std::memory_order_relaxed);

    char buf[1024];
    snprintf(buf, sizeof(buf),
             "request: rate=%d channels=2 format=I16 perf=LowLatency sharing=Exclusive "
             "srcQuality=Medium api=Unspecified",
             sampleRate);
    sDiagnostics = buf;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setSampleRate(sampleRate)
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
           ->setDataCallback(&sCallback);

    oboe::Result result = builder.openStream(sStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open audio stream: %s", oboe::convertToText(result));
        snprintf(buf, sizeof(buf), " | openStream FAILED: %s", oboe::convertToText(result));
        sDiagnostics += buf;
        sStream.reset();
        return;
    }

    snprintf(buf, sizeof(buf),
             " | opened: api=%s rate=%d channels=%d format=%s sharing=%s perf=%s "
             "framesPerBurst=%d bufferCapacity=%d bufferSize=%d",
             audioApiName(sStream->getAudioApi()),
             sStream->getSampleRate(),
             sStream->getChannelCount(),
             sStream->getFormat() == oboe::AudioFormat::I16 ? "I16" :
                sStream->getFormat() == oboe::AudioFormat::Float ? "Float" : "?",
             sharingModeName(sStream->getSharingMode()),
             perfModeName(sStream->getPerformanceMode()),
             sStream->getFramesPerBurst(),
             sStream->getBufferCapacityInFrames(),
             sStream->getBufferSizeInFrames());
    sDiagnostics += buf;

    result = sStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start audio stream: %s", oboe::convertToText(result));
        snprintf(buf, sizeof(buf), " | requestStart FAILED: %s", oboe::convertToText(result));
        sDiagnostics += buf;
        sStream->close();
        sStream.reset();
        return;
    }

    snprintf(buf, sizeof(buf), " | started state=%d", (int)sStream->getState());
    sDiagnostics += buf;
}

const char* nativeAudioGetDiagnostics() {
    return sDiagnostics.c_str();
}

void nativeAudioWrite(const int16_t* data, int32_t frames) {
    if (!sStream || frames <= 0) return;
    if (sMuted.load(std::memory_order_relaxed)) return;
    ringWrite(data, frames);
}

void nativeAudioSetMuted(bool muted) {
    sMuted.store(muted, std::memory_order_relaxed);
}

void nativeAudioPause() {
    if (sStream) {
        sStream->requestPause();
        sStream->waitForStateChange(oboe::StreamState::Pausing, nullptr, 100000000);
        sWritePos.store(0, std::memory_order_relaxed);
        sReadPos.store(0, std::memory_order_relaxed);
    }
}

void nativeAudioResume() {
    if (sStream) {
        sWritePos.store(0, std::memory_order_relaxed);
        sReadPos.store(0, std::memory_order_relaxed);
        sStream->requestStart();
    }
}

void nativeAudioStop() {
    if (sStream) {
        sStream->requestStop();
        sStream->close();
        sStream.reset();
    }
    sMuted.store(false, std::memory_order_relaxed);
}
