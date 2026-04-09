#include "native_audio.h"
#include <oboe/Oboe.h>
#include <atomic>
#include <android/log.h>

#define LOG_TAG "NativeAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::shared_ptr<oboe::AudioStream> sStream;
static std::atomic<bool> sMuted{false};

void nativeAudioInit(int32_t sampleRate) {
    nativeAudioStop();

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setSampleRate(sampleRate);

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

    auto result = sStream->write(data, frames, 1000000000);
    if (result != oboe::Result::OK) {
        LOGE("Audio write failed: %s", oboe::convertToText(result));
    }
}

void nativeAudioSetMuted(bool muted) {
    sMuted.store(muted, std::memory_order_relaxed);
}

void nativeAudioStop() {
    if (sStream) {
        sStream->requestStop();
        sStream->close();
        sStream.reset();
    }
    sMuted.store(false, std::memory_order_relaxed);
}
