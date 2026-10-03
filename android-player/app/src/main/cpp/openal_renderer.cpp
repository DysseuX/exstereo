/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "openal_renderer.h"

#define AL_ALEXT_PROTOTYPES
#include <AL/al.h>
#include <AL/alc.h>
#include <AL/alext.h>
#include <AL/efx.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <stdexcept>

namespace spatial {
namespace {
constexpr int kSources = 7;
constexpr int kSlice = 256;
// Pinned OpenAL Soft uses 24 future samples in core/resampler_limits.h even
// when source and device rates match. Keep 128 actual queued frames so the
// callback never fabricates future samples at render-call boundaries.
constexpr int kLatency = 128;
constexpr int kRing = 512;
constexpr int kWaveChannels[kSources] = {0, 1, 2, 4, 5, 6, 7};
constexpr float kRadians = 3.14159265358979323846f / 180.0f;

void checkAl(const char* operation) {
    const ALenum code = alGetError();
    if (code != AL_NO_ERROR)
        throw std::runtime_error(std::string("OpenAL ") + operation + ": " + std::to_string(code));
}

class ContextScope {
public:
    explicit ContextScope(ALCcontext* context) : previous_(alcGetThreadContext()) {
        if (!alcSetThreadContext(context))
            throw std::runtime_error("OpenAL cannot set thread-local context");
    }
    ~ContextScope() { alcSetThreadContext(previous_); }
private:
    ALCcontext* previous_;
};

struct Samples {
    std::array<float, kRing> ring{};
    int read = 0;
    int count = kLatency;
    bool underflow = false;

    void push(const float* interleaved, int frames, int channel) {
        if (count + frames > kRing)
            throw std::runtime_error("OpenAL input FIFO overflow");
        for (int i = 0; i < frames; ++i)
            ring[(read + count + i) % kRing] = interleaved[i * 8 + channel];
        count += frames;
    }

    static ALsizei AL_APIENTRY callback(void* opaque, void* destination, ALsizei bytes) noexcept {
        auto& self = *static_cast<Samples*>(opaque);
        auto* out = static_cast<float*>(destination);
        const int wanted = bytes / static_cast<ALsizei>(sizeof(float));
        const int available = std::min(wanted, self.count);
        for (int i = 0; i < available; ++i)
            out[i] = self.ring[(self.read + i) % kRing];
        self.read = (self.read + available) % kRing;
        self.count -= available;
        if (available != wanted) {
            // A noexcept C callback cannot propagate errors through OpenAL.
            // Complete this callback safely and report failure after rendering.
            std::fill(out + available, out + wanted, 0.0f);
            self.underflow = true;
        }
        return bytes;
    }
};
}

class OpenAlRenderer::Impl {
public:
    explicit Impl(int rate) : rate_(rate) {
        if (rate < 8000 || rate > 96000)
            throw std::invalid_argument("OpenAL sample rate must be 8000..96000 Hz");
        try { open(); } catch (...) { close(); throw; }
    }
    ~Impl() { close(); }

    void open() {
        if (!alcIsExtensionPresent(nullptr, "ALC_SOFT_loopback") ||
            !alcIsExtensionPresent(nullptr, "ALC_EXT_thread_local_context"))
            throw std::runtime_error("OpenAL loopback/thread-local extension unavailable");
        device_ = alcLoopbackOpenDeviceSOFT(nullptr);
        if (!device_) throw std::runtime_error("OpenAL cannot open loopback device");
        if (!alcIsExtensionPresent(device_, "ALC_SOFT_HRTF") ||
            !alcIsRenderFormatSupportedSOFT(device_, rate_, ALC_STEREO_SOFT, ALC_FLOAT_SOFT))
            throw std::runtime_error("OpenAL stereo-float HRTF format unavailable");

        ALCint count = 0;
        alcGetIntegerv(device_, ALC_NUM_HRTF_SPECIFIERS_SOFT, 1, &count);
        int builtin = -1;
        for (int i = 0; i < count; ++i) {
            const char* name = alcGetStringiSOFT(device_, ALC_HRTF_SPECIFIER_SOFT, i);
            if (name && std::strcmp(name, "Built-In HRTF") == 0) builtin = i;
        }
        if (builtin < 0)
            throw std::runtime_error("OpenAL embedded HRTF is unavailable (check hrtf-paths configuration)");

        const ALCint attributes[] = {
            ALC_FORMAT_CHANNELS_SOFT, ALC_STEREO_SOFT,
            ALC_FORMAT_TYPE_SOFT, ALC_FLOAT_SOFT,
            ALC_FREQUENCY, rate_,
            ALC_HRTF_SOFT, ALC_TRUE,
            ALC_HRTF_ID_SOFT, builtin,
            ALC_OUTPUT_LIMITER_SOFT, ALC_FALSE,
            ALC_MONO_SOURCES, kSources,
            ALC_STEREO_SOURCES, 0,
            ALC_MAX_AUXILIARY_SENDS, 0,
            0
        };
        context_ = alcCreateContext(device_, attributes);
        if (!context_) throw std::runtime_error("OpenAL cannot create HRTF loopback context");
        ALCint hrtf = 0;
        alcGetIntegerv(device_, ALC_HRTF_SOFT, 1, &hrtf);
        if (hrtf != ALC_TRUE) throw std::runtime_error("OpenAL did not activate HRTF");
        const char* actual = alcGetString(device_, ALC_HRTF_SPECIFIER_SOFT);
        if (!actual || std::strcmp(actual, "Built-In HRTF") != 0)
            throw std::runtime_error("OpenAL selected an unexpected HRTF");
        name_ = actual;

        ContextScope current(context_);
        if (!alIsExtensionPresent("AL_SOFT_callback_buffer") ||
            !alIsExtensionPresent("AL_EXT_FLOAT32") ||
            !alIsExtensionPresent("AL_SOFT_source_spatialize"))
            throw std::runtime_error("OpenAL callback/float/spatialization extension unavailable");
        alDistanceModel(AL_NONE);
        alDopplerFactor(0.0f);
        alListenerf(AL_GAIN, 1.0f);
        alGenSources(kSources, sources_.data());
        alGenBuffers(kSources, buffers_.data());
        checkAl("allocate sources");
        for (int i = 0; i < kSources; ++i) {
            alBufferCallbackSOFT(buffers_[i], AL_FORMAT_MONO_FLOAT32, rate_, Samples::callback, &samples_[i]);
            alSourcei(sources_[i], AL_BUFFER, static_cast<ALint>(buffers_[i]));
            alSourcei(sources_[i], AL_SOURCE_RELATIVE, AL_TRUE);
            alSourcei(sources_[i], AL_SOURCE_SPATIALIZE_SOFT, AL_TRUE);
            alSourcef(sources_[i], AL_ROLLOFF_FACTOR, 0.0f);
            alSourcef(sources_[i], AL_GAIN, 1.0f);
            alSourcef(sources_[i], AL_PITCH, 1.0f);
        }
        checkAl("configure callback sources");
        // Start only after first positions are known. All sources start in the
        // same batch; loopback advances solely when alcRenderSamplesSOFT runs.
    }

    void close() noexcept {
        if (context_) {
            if (alcGetThreadContext() == context_) alcSetThreadContext(nullptr);
            alcDestroyContext(context_);
            context_ = nullptr;
        }
        if (device_) { alcCloseDevice(device_); device_ = nullptr; }
    }

    void render(const float* input, float* output, int frames,
                const std::array<float, 7>& az, const std::array<float, 7>& el) {
        if (frames < 0 || (frames && (!input || !output)))
            throw std::invalid_argument("OpenAL invalid PCM block");
        for (int i = 0; i < kSources; ++i) {
            if (!std::isfinite(az[i]) || !std::isfinite(el[i]))
                throw std::invalid_argument("OpenAL non-finite speaker position");
        }
        if (!frames) return;
        std::lock_guard<std::mutex> lock(mutex_);
        ContextScope current(context_);
        for (int i = 0; i < kSources; ++i) {
            const float a = std::remainder(az[i], 360.0f) * kRadians;
            const float e = std::max(-90.0f, std::min(90.0f, el[i])) * kRadians;
            alSource3f(sources_[i], AL_POSITION,
                       std::sin(a) * std::cos(e), std::sin(e), -std::cos(a) * std::cos(e));
        }
        if (!playing_) {
            alSourcePlayv(kSources, sources_.data());
            playing_ = true;
        }
        checkAl("update positions");
        for (int offset = 0; offset < frames; ) {
            const int n = std::min(kSlice, frames - offset);
            const float* slice = input + static_cast<size_t>(offset) * 8;
            for (int i = 0; i < n * 8; ++i)
                if (!std::isfinite(slice[i])) throw std::invalid_argument("OpenAL non-finite PCM");
            for (int ch = 0; ch < kSources; ++ch) samples_[ch].push(slice, n, kWaveChannels[ch]);
            alcRenderSamplesSOFT(device_, output + static_cast<size_t>(offset) * 2, n);
            const auto error = alcGetError(device_);
            if (error != ALC_NO_ERROR)
                throw std::runtime_error("OpenAL loopback rendering failed: " + std::to_string(error));
            for (const auto& sample : samples_)
                if (sample.underflow) throw std::runtime_error("OpenAL callback input FIFO underflow");
            offset += n;
        }
    }

    int rate_;
    std::string name_;
    std::mutex mutex_;
    ALCdevice* device_ = nullptr;
    ALCcontext* context_ = nullptr;
    std::array<ALuint, kSources> sources_{};
    std::array<ALuint, kSources> buffers_{};
    std::array<Samples, kSources> samples_{};
    bool playing_ = false;
};

OpenAlRenderer::OpenAlRenderer(int rate) : impl_(new Impl(rate)) {}
OpenAlRenderer::~OpenAlRenderer() = default;
int OpenAlRenderer::latencyFrames() const { return kLatency; }
const std::string& OpenAlRenderer::hrtfName() const { return impl_->name_; }
void OpenAlRenderer::render(const float* input, float* output, int frames,
                            const std::array<float, 7>& az, const std::array<float, 7>& el) {
    impl_->render(input, output, frames, az, el);
}
void OpenAlRenderer::reset() {
    // A fresh context also clears the device's HRTF convolution history; source
    // rewind alone can leave a tail in the shared mixer's delay lines.
    std::unique_ptr<Impl> fresh(new Impl(impl_->rate_));
    impl_.swap(fresh);
}
}
