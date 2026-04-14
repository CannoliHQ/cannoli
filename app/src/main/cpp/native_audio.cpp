#include "native_audio.h"

#include <oboe/Oboe.h>
#include <oboe/FifoBuffer.h>

#ifndef OUTSIDE_SPEEX
#define OUTSIDE_SPEEX
#endif
#ifndef RANDOM_PREFIX
#define RANDOM_PREFIX cannoli
#endif
#include "speex_resampler.h"

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>

namespace {

constexpr int32_t CHANNELS      = 2;
constexpr int32_t OUTPUT_RATE   = 48000;

// Latency target used when Oboe isn't in LowLatency mode. FIFO capacity is
// expressed as a multiple of one video frame's worth of audio samples.
constexpr double BUFFER_SIZE_IN_VIDEO_FRAMES = 6.0;
constexpr double MIN_BUFFER_MS               = 32.0;

// PI controller for adaptive speex rate. Error is normalised as a fraction of
// target FIFO fill ∈ [-1, +1], so gains are unitless. Tick cadence is ~10
// callbacks (~200 ms at 962-frame bursts); smaller/more-frequent corrections
// produce smoother rate changes and avoid audible crackle that large
// speex_resampler_set_rate jumps can cause.
constexpr uint32_t ADAPT_TICK_CALLBACKS = 10;
constexpr double   ADAPT_KP             = 0.003; // max P contribution ≈ ±0.3%
constexpr double   ADAPT_KI             = 0.0002; // max I contribution ≈ ±1% at clamp
constexpr double   ADAPT_INTEGRAL_CLAMP = 50.0;
constexpr double   ADAPT_RATE_BAND      = 0.02;  // ±2% total deviation from nominal

struct AudioState : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
    std::shared_ptr<oboe::AudioStream> stream;
    std::unique_ptr<oboe::FifoBuffer>  fifo;
    std::unique_ptr<int16_t[]>         callbackScratch;
    size_t                             callbackScratchFrames = 0;
    std::unique_ptr<oboe::LatencyTuner> latencyTuner;

    SpeexResamplerState* resampler = nullptr;
    int32_t              inputRate = 0;
    int32_t              outputRate = OUTPUT_RATE;
    int32_t              adaptiveInRate = 0;
    double               adaptiveIntegral = 0.0;
    uint32_t             adaptiveCounter = 0;

    // Speex is stateful and doesn't always consume exactly (numFrames × ratio)
    // input per call — its phase accumulator means the consumed count varies
    // by a frame or two. We over-pull into this carry buffer, run speex, and
    // memmove any unconsumed tail back to the start for the next callback.
    // Without this, the tail either zero-pads (audible silence gap = crackle)
    // or loses frames (progressive misalignment over time).
    std::unique_ptr<int16_t[]> speexCarry;
    size_t                     speexCarryMax   = 0;
    int32_t                    speexCarryFrames = 0;

    double  contentRefreshRate = 60.0;
    int32_t fifoCapacityFrames = 0;
    bool    lowLatencyStream   = false;

    std::atomic<bool> running{false};
    std::atomic<bool> muted{false};
    std::atomic<bool> nonblock{false};
    std::atomic<bool> streamStarted{false};

    // Diagnostics — sampled by nativeAudioGetDiagnostics, written by both
    // the GL thread (writes) and the Oboe callback thread (everything else).
    // Relaxed ordering is fine — they're informational.
    std::atomic<uint64_t> statWrites{0};        // nativeAudioWrite calls
    std::atomic<uint64_t> statFramesIn{0};      // frames pushed into FIFO
    std::atomic<uint64_t> statWriteDrops{0};    // frames dropped (muted or FIFO full)
    std::atomic<uint64_t> statCallbacks{0};     // Oboe onAudioReady calls
    std::atomic<uint64_t> statFramesOut{0};     // frames written to output buffer
    std::atomic<uint64_t> statUnderfills{0};    // callbacks where FIFO didn't have enough
    std::atomic<uint64_t> statAdaptTicks{0};    // PI controller evaluations
    std::atomic<int32_t>  statLastFifoFill{0};  // FIFO frames available as of last callback
    std::atomic<int32_t>  statAdaptRate{0};     // current speex in-rate (after PI)
    std::atomic<int32_t>  statXRun{0};          // Oboe xRun count snapshot

    // AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* oboeStream, void* audioData, int32_t numFrames) override;

    // AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* oldStream, oboe::Result result) override;

    bool openStream();
    void closeStream();
    void tickAdaptiveRate();
};

AudioState g;
std::string gDiagnostics;
std::string gDiagnosticsHeader; // stable "request: ..." prefix set at init

int32_t roundToEven(int32_t v) { return (v / 2) * 2; }

bool AudioState::openStream() {
    // Reset the diagnostic line from the stable header. Without this, each
    // reopen (e.g. onErrorAfterClose recovery) stacks another "opened" entry
    // onto the existing string, which makes the session log unreadable.
    gDiagnostics = gDiagnosticsHeader;

    // Default to the safe (non-fast-mixer) Oboe path. The fast path is what
    // crackled under the original Oboe driver on Moorechip — tiny ~96-frame
    // callbacks force the resampler and PI controller to react per HAL burst
    // instead of per-frame, which is terrible. No caller currently opts into
    // LowLatency, so it stays off.
    lowLatencyStream = false;

    double maxLatencyMs =
            std::max(MIN_BUFFER_MS,
                     (BUFFER_SIZE_IN_VIDEO_FRAMES / contentRefreshRate) * 1000.0);

    double sampleRateDivisor = 500.0 / maxLatencyMs;
    fifoCapacityFrames = roundToEven(
            (int32_t)std::lround((double)inputRate / sampleRateDivisor));
    if (fifoCapacityFrames < 512) fifoCapacityFrames = 512;

    oboe::AudioStreamBuilder builder;
    builder.setChannelCount(CHANNELS)
           ->setDirection(oboe::Direction::Output)
           ->setFormat(oboe::AudioFormat::I16)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    if (lowLatencyStream) {
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    } else {
        builder.setFramesPerCallback(fifoCapacityFrames / 10);
    }

    oboe::Result r = builder.openStream(stream);
    if (r != oboe::Result::OK) {
        char buf[128];
        snprintf(buf, sizeof(buf), " | openStream FAILED: %s", oboe::convertToText(r));
        gDiagnostics += buf;
        stream.reset();
        return false;
    }

    outputRate = stream->getSampleRate();

    // Speex resampler lives on the Oboe callback thread, not the GL thread.
    // Rate changes during playback go through speex_resampler_set_rate so the
    // stateful phase accumulator stays coherent.
    adaptiveInRate = inputRate;
    adaptiveIntegral = 0.0;
    adaptiveCounter = 0;
    statAdaptRate.store(adaptiveInRate, std::memory_order_relaxed);
    int err = 0;
    resampler = speex_resampler_init(
            (spx_uint32_t)CHANNELS,
            (spx_uint32_t)inputRate,
            (spx_uint32_t)outputRate,
            SPEEX_RESAMPLER_QUALITY_DEFAULT,
            &err);
    if (!resampler || err != RESAMPLER_ERR_SUCCESS) {
        char ebuf[96];
        snprintf(ebuf, sizeof(ebuf), " | speex init FAILED: %d", err);
        gDiagnostics += ebuf;
        return false;
    }

    // FIFO holds inputRate-side samples. 2 channels interleaved.
    fifo = std::make_unique<oboe::FifoBuffer>(
            (uint32_t)(CHANNELS * sizeof(int16_t)),
            (uint32_t)fifoCapacityFrames);

    // Scratch buffer for pulling from FIFO before the resampler. Sized to
    // the largest plausible callback pull at worst-case ratio (downsample
    // by ~3× upper bound), plus a pad.
    callbackScratchFrames = (size_t)(fifoCapacityFrames * 2);
    callbackScratch = std::unique_ptr<int16_t[]>(
            new int16_t[callbackScratchFrames * CHANNELS]);

    // Carry buffer for unconsumed speex input across callbacks. Max size
    // only needs to hold one callback's worth plus margin.
    speexCarryMax = (size_t)(fifoCapacityFrames);
    speexCarry = std::unique_ptr<int16_t[]>(
            new int16_t[speexCarryMax * CHANNELS]);
    speexCarryFrames = 0;

    latencyTuner = std::make_unique<oboe::LatencyTuner>(*stream);

    char buf[384];
    snprintf(buf, sizeof(buf),
             " | opened: Oboe, driver=%s, perfMode=%s, inRate=%d, outRate=%d, "
             "fifoCap=%d frames, framesPerBurst=%d",
             stream->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSLES",
             lowLatencyStream ? "LowLatency" : "None",
             inputRate, outputRate,
             fifoCapacityFrames, stream->getFramesPerBurst());
    gDiagnostics += buf;
    return true;
}

void AudioState::closeStream() {
    if (stream) {
        stream->requestStop();
        stream->close();
        stream.reset();
    }
    latencyTuner.reset();
    fifo.reset();
    callbackScratch.reset();
    callbackScratchFrames = 0;
    speexCarry.reset();
    speexCarryMax = 0;
    speexCarryFrames = 0;
    if (resampler) {
        speex_resampler_destroy(resampler);
        resampler = nullptr;
    }
    adaptiveIntegral = 0.0;
    adaptiveCounter = 0;
}

// PI controller tick. Error is (fifoFill - target) normalised to [-1, +1]
// where -1 is empty and +1 is full. Positive error means producer is ahead of
// us — we pull MORE per callback by claiming a HIGHER input rate to speex,
// which makes the resampler consume more FIFO frames per output frame.
// Gains are tuned so each tick moves the rate by sub-percent amounts; rate
// slams from big deltas show up audibly as crackle because every meaningful
// jump forces speex_resampler_set_rate to rebuild its filter table.
void AudioState::tickAdaptiveRate() {
    if (!resampler || !fifo) return;
    double capacity = (double)fifo->getBufferCapacityInFrames();
    double available = (double)fifo->getFullFramesAvailable();
    if (capacity <= 0.0) return;
    double target = capacity * 0.5;
    double error  = (available - target) / target; // ∈ [-1, +1]

    adaptiveIntegral += error;
    if (adaptiveIntegral > ADAPT_INTEGRAL_CLAMP)  adaptiveIntegral = ADAPT_INTEGRAL_CLAMP;
    if (adaptiveIntegral < -ADAPT_INTEGRAL_CLAMP) adaptiveIntegral = -ADAPT_INTEGRAL_CLAMP;

    double adjustment = ADAPT_KP * error + ADAPT_KI * adaptiveIntegral;
    if (adjustment >  ADAPT_RATE_BAND) adjustment =  ADAPT_RATE_BAND;
    if (adjustment < -ADAPT_RATE_BAND) adjustment = -ADAPT_RATE_BAND;

    int32_t newInRate =
        (int32_t)std::lround((double)inputRate * (1.0 + adjustment));

    // Only call speex_resampler_set_rate on a meaningful change. Any set_rate
    // call triggers a filter rebuild; avoiding no-op calls keeps the callback
    // thread cheap AND avoids micro-glitches from back-to-back rebuilds.
    if (std::abs(newInRate - adaptiveInRate) >= 4) {
        adaptiveInRate = newInRate;
        statAdaptRate.store(newInRate, std::memory_order_relaxed);
        speex_resampler_set_rate(resampler,
                                 (spx_uint32_t)newInRate,
                                 (spx_uint32_t)outputRate);
    }
    statAdaptTicks.fetch_add(1, std::memory_order_relaxed);
}

oboe::DataCallbackResult AudioState::onAudioReady(
        oboe::AudioStream* oboeStream, void* audioData, int32_t numFrames) {
    statCallbacks.fetch_add(1, std::memory_order_relaxed);

    if (++adaptiveCounter >= ADAPT_TICK_CALLBACKS) {
        adaptiveCounter = 0;
        tickAdaptiveRate();
    }

    int16_t* out = reinterpret_cast<int16_t*>(audioData);
    if (!resampler || !fifo || !speexCarry) {
        memset(out, 0, (size_t)numFrames * CHANNELS * sizeof(int16_t));
        return oboe::DataCallbackResult::Continue;
    }

    // Target input frames needed to produce `numFrames` output at the current
    // claimed rate, plus a small safety margin. Speex is stateful so the
    // exact consumed count per call isn't deterministic — over-pulling into
    // the carry buffer and preserving any unconsumed tail across callbacks
    // is how you wrap a stateful resampler in an output-driven callback.
    double ratio = (double)adaptiveInRate / (double)outputRate;
    int32_t targetIn = (int32_t)std::ceil((double)numFrames * ratio) + 8;
    if ((size_t)targetIn > speexCarryMax) targetIn = (int32_t)speexCarryMax;

    // Fill the carry buffer up to targetIn with fresh data from the FIFO,
    // appended after whatever leftover is already there.
    int32_t need = targetIn - speexCarryFrames;
    if (need > 0) {
        int32_t pulled = (int32_t)fifo->read(
                (uint8_t*)(speexCarry.get() + speexCarryFrames * CHANNELS),
                need);
        if (pulled < need) {
            // FIFO is running dry — zero-pad so speex doesn't see garbage.
            // The PI controller will notice the drain next tick and slow us
            // down to let the producer catch up.
            memset(speexCarry.get() + (speexCarryFrames + pulled) * CHANNELS,
                   0,
                   (size_t)(need - pulled) * CHANNELS * sizeof(int16_t));
            statUnderfills.fetch_add(1, std::memory_order_relaxed);
        }
        speexCarryFrames += need;
    }

    spx_uint32_t inLen  = (spx_uint32_t)speexCarryFrames;
    spx_uint32_t outLen = (spx_uint32_t)numFrames;
    speex_resampler_process_interleaved_int(
            resampler, speexCarry.get(), &inLen, out, &outLen);

    // Preserve any unconsumed tail for the next callback.
    int32_t leftover = speexCarryFrames - (int32_t)inLen;
    if (leftover > 0) {
        memmove(speexCarry.get(),
                speexCarry.get() + (size_t)inLen * CHANNELS,
                (size_t)leftover * CHANNELS * sizeof(int16_t));
    } else {
        leftover = 0;
    }
    speexCarryFrames = leftover;

    // If speex somehow produced fewer frames than requested, zero the tail.
    // With the over-pull + carry strategy this should be rare.
    if ((int32_t)outLen < numFrames) {
        memset(out + (size_t)outLen * CHANNELS, 0,
               (size_t)(numFrames - (int32_t)outLen) * CHANNELS * sizeof(int16_t));
    }

    if (g.muted.load(std::memory_order_relaxed)) {
        memset(audioData, 0, (size_t)numFrames * CHANNELS * sizeof(int16_t));
    }

    statFramesOut.fetch_add((uint64_t)numFrames, std::memory_order_relaxed);
    statLastFifoFill.store((int32_t)fifo->getFullFramesAvailable(),
                           std::memory_order_relaxed);
    if (latencyTuner) latencyTuner->tune();
    auto xr = oboeStream->getXRunCount();
    if (xr) statXRun.store(xr.value(), std::memory_order_relaxed);

    return oboe::DataCallbackResult::Continue;
}

void AudioState::onErrorAfterClose(oboe::AudioStream* /*oldStream*/, oboe::Result result) {
    if (result != oboe::Result::ErrorDisconnected) return;
    // Device changed (headphones unplugged, BT handoff, etc). Reopen on the
    // same params and start immediately — the FIFO is likely mid-session and
    // already has queued data, so there's no deferred-start window here.
    closeStream();
    streamStarted.store(false, std::memory_order_release);
    if (!openStream()) {
        gDiagnostics += " | recovery openStream FAILED";
        return;
    }
    if (running.load(std::memory_order_acquire)) {
        stream->requestStart();
        streamStarted.store(true, std::memory_order_release);
    }
    gDiagnostics += " | recovered from ErrorDisconnected";
}

} // namespace

void nativeAudioInit(int32_t sampleRate) {
    nativeAudioStop();

    g.inputRate = sampleRate;
    g.running.store(false, std::memory_order_relaxed);

    // Reset per-session stat counters. These are atomic members of the
    // namespace-static AudioState g, so without explicit resets they carry
    // over between play sessions in the same process and the diagnostic
    // output looks nonsensical ("writes=2273 at t=16ms after init").
    g.statWrites.store(0, std::memory_order_relaxed);
    g.statFramesIn.store(0, std::memory_order_relaxed);
    g.statWriteDrops.store(0, std::memory_order_relaxed);
    g.statCallbacks.store(0, std::memory_order_relaxed);
    g.statFramesOut.store(0, std::memory_order_relaxed);
    g.statUnderfills.store(0, std::memory_order_relaxed);
    g.statAdaptTicks.store(0, std::memory_order_relaxed);
    g.statLastFifoFill.store(0, std::memory_order_relaxed);
    g.statAdaptRate.store(sampleRate, std::memory_order_relaxed);
    g.statXRun.store(0, std::memory_order_relaxed);

    char buf[256];
    snprintf(buf, sizeof(buf),
             "request: inRate=%d outRate=%d channels=%d bufFrames=%.1f minMs=%.0f",
             sampleRate, OUTPUT_RATE, CHANNELS,
             BUFFER_SIZE_IN_VIDEO_FRAMES, MIN_BUFFER_MS);
    gDiagnosticsHeader = buf;
    gDiagnostics = gDiagnosticsHeader;

    if (!g.openStream()) {
        g.closeStream();
        return;
    }

    // Do NOT start the Oboe stream yet. The core hasn't pushed any samples
    // into the FIFO, so callbacks would fire against an empty buffer and
    // emit ~1 sec of zero-padded garbage (underfills + speex transients).
    // Deferring requestStart() until the first nativeAudioWrite means the
    // audio thread only starts pulling once there's real data to consume.
    g.streamStarted.store(false, std::memory_order_release);
    g.running.store(true, std::memory_order_release);
    gDiagnostics += " | opened (start deferred)";
}

void nativeAudioWrite(const int16_t* data, int32_t frames) {
    if (!g.running.load(std::memory_order_acquire) || frames <= 0) return;
    g.statWrites.fetch_add(1, std::memory_order_relaxed);
    if (g.muted.load(std::memory_order_relaxed)) {
        g.statWriteDrops.fetch_add((uint64_t)frames, std::memory_order_relaxed);
        return;
    }
    if (!g.fifo) return;

    int32_t written = (int32_t)g.fifo->write(
            reinterpret_cast<const uint8_t*>(data), (int32_t)frames);
    if (written > 0) {
        g.statFramesIn.fetch_add((uint64_t)written, std::memory_order_relaxed);
    }
    if (written < frames) {
        g.statWriteDrops.fetch_add((uint64_t)(frames - written),
                                    std::memory_order_relaxed);
    }

    // First real write — now there's data for the audio thread to pull, so
    // start the Oboe stream. Everything after this uses the stream normally.
    if (!g.streamStarted.load(std::memory_order_acquire) && g.stream) {
        bool expected = false;
        if (g.streamStarted.compare_exchange_strong(
                expected, true, std::memory_order_acq_rel)) {
            g.stream->requestStart();
        }
    }
}

void nativeAudioSetMuted(bool muted) {
    g.muted.store(muted, std::memory_order_relaxed);
}

void nativeAudioSetNonblock(bool nonblock) {
    g.nonblock.store(nonblock, std::memory_order_relaxed);
}

void nativeAudioPause() {
    if (g.stream) g.stream->requestPause();
}

void nativeAudioResume() {
    if (g.stream) g.stream->requestStart();
}

void nativeAudioStop() {
    g.running.store(false, std::memory_order_release);
    g.closeStream();
    g.streamStarted.store(false, std::memory_order_relaxed);
    g.muted.store(false, std::memory_order_relaxed);
    g.nonblock.store(false, std::memory_order_relaxed);
}

const char* nativeAudioGetDiagnostics() {
    static std::string composed;
    char tail[384];
    snprintf(tail, sizeof(tail),
             " | stats: writes=%llu in=%llu drops=%llu cb=%llu out=%llu underfill=%llu "
             "adaptTicks=%llu fifoFill=%d xRun=%d adaptRate=%d",
             (unsigned long long)g.statWrites.load(std::memory_order_relaxed),
             (unsigned long long)g.statFramesIn.load(std::memory_order_relaxed),
             (unsigned long long)g.statWriteDrops.load(std::memory_order_relaxed),
             (unsigned long long)g.statCallbacks.load(std::memory_order_relaxed),
             (unsigned long long)g.statFramesOut.load(std::memory_order_relaxed),
             (unsigned long long)g.statUnderfills.load(std::memory_order_relaxed),
             (unsigned long long)g.statAdaptTicks.load(std::memory_order_relaxed),
             g.statLastFifoFill.load(std::memory_order_relaxed),
             g.statXRun.load(std::memory_order_relaxed),
             g.statAdaptRate.load(std::memory_order_relaxed));
    composed = gDiagnostics + tail;
    return composed.c_str();
}
