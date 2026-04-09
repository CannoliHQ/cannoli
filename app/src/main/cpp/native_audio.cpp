#include "native_audio.h"
#include <oboe/Oboe.h>
#include <atomic>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "NativeAudio"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setSampleRate(sampleRate)
           ->setDataCallback(&sCallback);

    oboe::Result result = builder.openStream(sStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open audio stream: %s", oboe::convertToText(result));
        sStream.reset();
        return;
    }

    result = sStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start audio stream: %s", oboe::convertToText(result));
        sStream->close();
        sStream.reset();
    }
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
