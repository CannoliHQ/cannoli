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
#include <cstring>
#include <memory>
#include <mutex>
#include <shared_mutex>
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
    std::shared_ptr<oboe::AudioStream>  stream;
    std::unique_ptr<oboe::FifoBuffer>   fifo;
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

    int32_t fifoCapacityFrames = 0;
    bool    lowLatencyStream   = false;

    std::atomic<bool> running{false};
    std::atomic<bool> muted{false};
    std::atomic<bool> nonblock{false};
    std::atomic<bool> streamStarted{false};

    std::atomic<uint64_t> statWrites{0};            // nativeAudioWrite calls (all)
    std::atomic<uint64_t> statFramesIn{0};          // frames pushed into FIFO
    std::atomic<uint64_t> statMuteDrops{0};         // frames dropped because muted (fast-forward)
    std::atomic<uint64_t> statFifoFullDrops{0};     // frames dropped because FIFO overflowed
    std::atomic<uint64_t> statContentionDrops{0};   // frames dropped due to try_lock failure during a lifecycle op
    std::atomic<uint64_t> statCallbacks{0};         // Oboe onAudioReady calls
    std::atomic<uint64_t> statFramesOut{0};         // frames written to output buffer
    std::atomic<uint64_t> statUnderfills{0};        // callbacks where FIFO didn't have enough
    std::atomic<uint64_t> statAdaptTicks{0};        // PI controller evaluations
    std::atomic<int32_t>  statLastFifoFill{0};      // FIFO frames available as of last callback
    std::atomic<int32_t>  statAdaptRate{0};         // current speex in-rate (after PI)
    std::atomic<int32_t>  statXRun{0};              // Oboe xRun count snapshot

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* oboeStream, void* audioData, int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream* oldStream, oboe::Result result) override;

    bool openStream();
    void closeStream();
    void tickAdaptiveRate();
};

AudioState g;
std::string gDiagnostics;
std::string gDiagnosticsHeader; // stable "request: ..." prefix set at init

// Lifecycle lock. The hot writer path (nativeAudioWrite) and the stream-touching
// control entry points (pause/resume) acquire shared ownership. Teardown and
// rebuild (nativeAudioInit, nativeAudioStop, onErrorAfterClose) acquire exclusive
// ownership, which blocks until all in-flight writers have exited. The audio
// data callback is NOT gated by this lock — Oboe's stream->close() already fences
// the data callback out before the exclusive holder can drop state.
std::shared_mutex gLifecycleMutex;

// Diagnostics string lock. Protects the gDiagnostics / gDiagnosticsHeader
// std::string globals, which are read by the periodic diagnostics poller from
// the Kotlin main thread and written by init / stop / openStream / error
// recovery on two or three other threads. std::string is not thread-safe for
// concurrent read and write, so every touch of these strings must hold this
// mutex. It's a separate lock from gLifecycleMutex so the poll never blocks
// writers, and lifecycle operations never block on a slow poll reader.
std::mutex gDiagnosticsMutex;

int32_t floorToEven(int32_t v) { return (v / 2) * 2; }

bool AudioState::openStream() {
    // Reset the diagnostic line from the stable header. Without this, each
    // reopen (e.g. onErrorAfterClose recovery) stacks another "opened" entry
    // onto the existing string, which makes the session log unreadable.
    {
        std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
        gDiagnostics = gDiagnosticsHeader;
    }

    // Default to the safe (non-fast-mixer) Oboe path. The fast path is what
    // crackled under the original Oboe driver on Moorechip — tiny ~96-frame
    // callbacks force the resampler and PI controller to react per HAL burst
    // instead of per-frame, which is terrible. No caller currently opts into
    // LowLatency, so it stays off.
    lowLatencyStream = false;

    // Assume 60 Hz content. Per-core fps isn't plumbed through from Kotlin
    // yet; 50 Hz PAL cores end up with a slightly smaller FIFO than this
    // formula implies, but MIN_BUFFER_MS clamps the result to 32 ms either
    // way, which dominates the PAL case anyway. Wire this to avInfo.fps if
    // latency tuning per core ever becomes interesting.
    constexpr double ASSUMED_CONTENT_FPS = 60.0;
    double maxLatencyMs =
            std::max(MIN_BUFFER_MS,
                     (BUFFER_SIZE_IN_VIDEO_FRAMES / ASSUMED_CONTENT_FPS) * 1000.0);

    double sampleRateDivisor = 500.0 / maxLatencyMs;
    fifoCapacityFrames = floorToEven(
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
        // floorToEven to keep the callback size on a whole-frame boundary
        // and avoid pathologically small sizes when fifoCapacityFrames is
        // near its 512-frame floor.
        int32_t framesPerCb = floorToEven(fifoCapacityFrames / 10);
        if (framesPerCb < 64) framesPerCb = 64;
        builder.setFramesPerCallback(framesPerCb);
    }

    oboe::Result r = builder.openStream(stream);
    if (r != oboe::Result::OK) {
        char buf[128];
        snprintf(buf, sizeof(buf), " | openStream FAILED: %s", oboe::convertToText(r));
        {
            std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
            gDiagnostics += buf;
        }
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
        {
            std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
            gDiagnostics += ebuf;
        }
        return false;
    }

    // FIFO holds inputRate-side samples. 2 channels interleaved.
    fifo = std::make_unique<oboe::FifoBuffer>(
            (uint32_t)(CHANNELS * sizeof(int16_t)),
            (uint32_t)fifoCapacityFrames);

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
    {
        std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
        gDiagnostics += buf;
    }
    return true;
}

void AudioState::closeStream() {
    // ORDERING IS LOAD-BEARING. stream->close() blocks until the data callback
    // thread has exited its current invocation, which fences out any pending
    // onAudioReady that might still be inside latencyTuner->tune(),
    // fifo->read(), speex_resampler_process_interleaved_int(), or the
    // speexCarry memmove. Only after close() has returned is it safe to drop
    // the unique_ptrs and destroy the resampler. Do not reorder these
    // statements without understanding the invariant — no explicit
    // synchronization protects the data callback's reach-through accesses,
    // only this close fence.
    if (stream) {
        stream->requestStop();
        stream->close();
        stream.reset();
    }
    latencyTuner.reset();
    fifo.reset();
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
    // appended after whatever leftover is already there. Any gap between
    // what we asked for and what the FIFO had is zero-padded so speex
    // doesn't read uninitialised memory; the padding still counts against
    // speexCarryFrames because the buffer is fully initialised afterward.
    int32_t need = targetIn - speexCarryFrames;
    if (need > 0) {
        int32_t pulled = (int32_t)fifo->read(
                (uint8_t*)(speexCarry.get() + speexCarryFrames * CHANNELS),
                need);
        if (pulled < need) {
            // FIFO is running dry — zero-pad the rest. The PI controller
            // will notice the drain next tick and slow production down
            // until the producer catches up.
            memset(speexCarry.get() + (speexCarryFrames + pulled) * CHANNELS,
                   0,
                   (size_t)(need - pulled) * CHANNELS * sizeof(int16_t));
            statUnderfills.fetch_add(1, std::memory_order_relaxed);
        }
        // After this block speexCarryFrames == targetIn, either from real
        // FIFO data or from real-data-plus-zero-pad.
        speexCarryFrames = targetIn;
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

    if (muted.load(std::memory_order_relaxed)) {
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
    // Log every error type (not just ErrorDisconnected) so silent failures
    // are at least observable in the session log.
    if (result != oboe::Result::ErrorDisconnected) {
        char buf[128];
        snprintf(buf, sizeof(buf), " | onErrorAfterClose: %s (ignored)",
                 oboe::convertToText(result));
        std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
        gDiagnostics += buf;
        return;
    }

    // Device changed (headphones unplugged, BT handoff, etc). Reopen on the
    // same params and start immediately — the FIFO is likely mid-session and
    // already has queued data, so there's no deferred-start window here.
    //
    // Exclusive lock serialises this recovery path against nativeAudioStop
    // and nativeAudioInit on the main/GL thread. Without it, a concurrent
    // stop during a device disconnect could double-free AudioState members.
    std::unique_lock<std::shared_mutex> lk(gLifecycleMutex);
    if (!running.load(std::memory_order_acquire)) {
        // Driver was stopped between the disconnect event and us getting the
        // lock. Don't reopen — leave everything torn down.
        return;
    }
    closeStream();
    streamStarted.store(false, std::memory_order_release);
    if (!openStream()) {
        // Reopen failed. Clear running so subsequent nativeAudioWrite calls
        // bail at the running check instead of falling through to silent
        // null-fifo drops. The driver is now in a clean "stopped" state —
        // Kotlin can detect the failure by observing the diagnostics string
        // or by calling nativeAudioInit again to force a fresh retry.
        running.store(false, std::memory_order_release);
        streamStarted.store(false, std::memory_order_relaxed);
        std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
        gDiagnostics += " | recovery openStream FAILED — driver stopped";
        return;
    }
    stream->requestStart();
    streamStarted.store(true, std::memory_order_release);
    {
        std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
        gDiagnostics += " | recovered from ErrorDisconnected";
    }
}

} // namespace

void nativeAudioInit(int32_t sampleRate) {
    nativeAudioStop();

    std::unique_lock<std::shared_mutex> lk(gLifecycleMutex);
    g.inputRate = sampleRate;
    g.running.store(false, std::memory_order_relaxed);

    // Reset per-session stat counters. These are atomic members of the
    // namespace-static AudioState g, so without explicit resets they carry
    // over between play sessions in the same process and the diagnostic
    // output looks nonsensical ("writes=2273 at t=16ms after init").
    g.statWrites.store(0, std::memory_order_relaxed);
    g.statFramesIn.store(0, std::memory_order_relaxed);
    g.statMuteDrops.store(0, std::memory_order_relaxed);
    g.statFifoFullDrops.store(0, std::memory_order_relaxed);
    g.statContentionDrops.store(0, std::memory_order_relaxed);
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
    {
        std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
        gDiagnosticsHeader = buf;
        gDiagnostics = gDiagnosticsHeader;
    }

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
    {
        std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
        gDiagnostics += " | opened (start deferred)";
    }
}

void nativeAudioWrite(const int16_t* data, int32_t frames) {
    if (frames <= 0) return;
    // Count every call up front, including ones dropped on the try_lock
    // failure path below. statWrites is "how often the core called us";
    // the split between successful and dropped is reflected in
    // statFramesIn vs statWriteDrops.
    g.statWrites.fetch_add(1, std::memory_order_relaxed);

    // Shared lock prevents nativeAudioStop / nativeAudioInit / onErrorAfterClose
    // from destroying g.fifo or g.stream while this write is in flight.
    // Uncontested shared acquisition is a couple of atomics, cheap enough for
    // the ~60 Hz producer rate.
    //
    // try_to_lock: if an exclusive holder (recovery, stop, or init) is active,
    // we DROP the write rather than blocking the GL thread. A recovery is
    // ~100-200ms of exclusive work, and rapid headphone plug/unplug can fire
    // multiple back-to-back. Blocking the writer for that whole window stalls
    // retro_run and tanks video fps. Dropping the frame produces a brief
    // audible glitch instead — much better than a visible game stutter.
    std::shared_lock<std::shared_mutex> lk(gLifecycleMutex, std::try_to_lock);
    if (!lk.owns_lock()) {
        g.statContentionDrops.fetch_add((uint64_t)frames, std::memory_order_relaxed);
        return;
    }
    if (!g.running.load(std::memory_order_acquire)) return;
    if (g.muted.load(std::memory_order_relaxed)) {
        g.statMuteDrops.fetch_add((uint64_t)frames, std::memory_order_relaxed);
        return;
    }
    if (!g.fifo) return;

    int32_t written = (int32_t)g.fifo->write(
            reinterpret_cast<const uint8_t*>(data), (int32_t)frames);
    if (written > 0) {
        g.statFramesIn.fetch_add((uint64_t)written, std::memory_order_relaxed);
    }
    if (written < frames) {
        g.statFifoFullDrops.fetch_add((uint64_t)(frames - written),
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
    std::shared_lock<std::shared_mutex> lk(gLifecycleMutex);
    if (g.stream) g.stream->requestPause();
}

void nativeAudioResume() {
    std::shared_lock<std::shared_mutex> lk(gLifecycleMutex);
    if (g.stream) g.stream->requestStart();
}

void nativeAudioStop() {
    // Setting running=false inside the exclusive lock guarantees that by the
    // time closeStream() runs, no shared-lock holder (writer) can see a
    // running==true state and proceed to touch g.fifo / g.stream.
    std::unique_lock<std::shared_mutex> lk(gLifecycleMutex);
    g.running.store(false, std::memory_order_release);
    g.closeStream();
    g.streamStarted.store(false, std::memory_order_release);
    g.muted.store(false, std::memory_order_relaxed);
    g.nonblock.store(false, std::memory_order_relaxed);
    // Clear the diagnostics strings so any debug poll between stop and the
    // next init reports empty state instead of stale previous-session text.
    {
        std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
        gDiagnostics.clear();
        gDiagnosticsHeader.clear();
    }
}

const char* nativeAudioGetDiagnostics() {
    static std::string composed;
    char tail[512];
    snprintf(tail, sizeof(tail),
             " | stats: writes=%llu in=%llu muteDrops=%llu fifoFullDrops=%llu "
             "contentionDrops=%llu cb=%llu out=%llu underfill=%llu adaptTicks=%llu "
             "fifoFill=%d xRun=%d adaptRate=%d",
             (unsigned long long)g.statWrites.load(std::memory_order_relaxed),
             (unsigned long long)g.statFramesIn.load(std::memory_order_relaxed),
             (unsigned long long)g.statMuteDrops.load(std::memory_order_relaxed),
             (unsigned long long)g.statFifoFullDrops.load(std::memory_order_relaxed),
             (unsigned long long)g.statContentionDrops.load(std::memory_order_relaxed),
             (unsigned long long)g.statCallbacks.load(std::memory_order_relaxed),
             (unsigned long long)g.statFramesOut.load(std::memory_order_relaxed),
             (unsigned long long)g.statUnderfills.load(std::memory_order_relaxed),
             (unsigned long long)g.statAdaptTicks.load(std::memory_order_relaxed),
             g.statLastFifoFill.load(std::memory_order_relaxed),
             g.statXRun.load(std::memory_order_relaxed),
             g.statAdaptRate.load(std::memory_order_relaxed));
    // Snapshot gDiagnostics under the string lock, then concatenate with the
    // unlocked tail. Holding the mutex only across the copy keeps the critical
    // section short and never overlaps with the snprintf above.
    std::string snapshot;
    {
        std::lock_guard<std::mutex> dlk(gDiagnosticsMutex);
        snapshot = gDiagnostics;
    }
    composed = snapshot + tail;
    return composed.c_str();
}
