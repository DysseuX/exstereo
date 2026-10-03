/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "spatial_engine.h"
#include "FreeSurround/FreeSurroundDecoder.h"
#include "phonon.h"
#include "phonon_version.h"
#include "openal_renderer.h"
#include "pae_upmixer.h"
#include "ffmpeg_filters.h"
#include "stereo_widener.h"
#include "advanced_wideners.h"
#include "virtual_room.h"

#include <algorithm>
#include <cmath>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <string>

namespace spatial {
namespace {
constexpr int kBlock = 1024;
constexpr int kHalf = kBlock / 2;
constexpr int kHrtfBlock = 256;
constexpr float kPi = 3.14159265358979323846f;
constexpr float kSqrtHalf = 0.7071067811865475f;
constexpr int kWaveFromFreeSurround[8] = {0, 2, 1, 7, 5, 6, 3, 4};
constexpr int kSpeakerWaveChannels[7] = {0, 1, 2, 4, 5, 6, 7};
// Steam Audio's HRTF creation uses shared initialization state. A background
// SOFA import and playback recreation must not initialize it concurrently.
std::mutex steamLifecycleMutex;

float bounded(float value, float low, float high, float fallback) {
    return std::isfinite(value) ? std::max(low, std::min(value, high)) : fallback;
}
float safeSample(float value) { return bounded(value, -16.0f, 16.0f, 0.0f); }
float wrapped(float degrees) { return std::remainder(degrees, 360.0f); }
void checked(IPLerror result, const char* operation) {
    if (result != IPL_STATUS_SUCCESS)
        throw std::runtime_error(std::string("Steam Audio: ") + operation + " failed (" + std::to_string(result) + ")");
}
Settings sanitized(const Settings& input) {
    Settings s = input;
    if (s.mode < 0 || s.mode > 2) throw std::invalid_argument("Unknown spatial mode");
    if (s.wideningAlgorithm < 0 || s.wideningAlgorithm > 10) throw std::invalid_argument("Unknown widening algorithm");
    s.width = bounded(s.width, 0, 2, 1);
    s.surroundGain = bounded(s.surroundGain, 0, 2, 0.5f);
    s.lfeGain = bounded(s.lfeGain, 0, 2, 1);
    s.hrtfBlend = bounded(s.hrtfBlend, 0, 1, 1);
    s.listenerYaw = bounded(s.listenerYaw, -180, 180, 0);
    s.listenerPitch = bounded(s.listenerPitch, -60, 60, 0);
    s.hrtfBalanceDb = bounded(s.hrtfBalanceDb, -6, 6, 0);
    s.hrtfGainDb = bounded(s.hrtfGainDb, -12, 6, 0);
    s.wideningAmount = bounded(s.wideningAmount, 0, 1, .5f);
    s.widenerLow = bounded(s.widenerLow, 0, 1, 0);
    s.widenerHigh = bounded(s.widenerHigh, 0, 1, .5f);
    s.widenerCrossover = bounded(s.widenerCrossover, 200, 4000, 1000);
    s.bandLowWidth = bounded(s.bandLowWidth, 0, 2, 1);
    s.bandMidWidth = bounded(s.bandMidWidth, 0, 2, 1.25f);
    s.bandHighWidth = bounded(s.bandHighWidth, 0, 2, 1.5f);
    s.bandLowCrossover = bounded(s.bandLowCrossover, 80, 1000, 200);
    s.bandHighCrossover = bounded(s.bandHighCrossover, 1500, 8000, 3000);
    s.paeAmbientWidth = bounded(s.paeAmbientWidth, 0, 3, 1.5f);
    s.paeAmbienceGainDb = bounded(s.paeAmbienceGainDb, -12, 6, 0);
    s.sideSynthesisAmount = bounded(s.sideSynthesisAmount, 0, 1, .5f);
    s.sideHighpass = bounded(s.sideHighpass, 20, 1000, 200);
    s.shuffleAmount = bounded(s.shuffleAmount, -1, 1, .5f);
    s.shuffleFrequency = bounded(s.shuffleFrequency, 100, 2000, 700);
    s.microCents = bounded(s.microCents, 0, 20, 6);
    s.microDelayMs = bounded(s.microDelayMs, 5, 30, 12);
    s.roomMix = bounded(s.roomMix, 0, 1, .15f);
    s.roomSize = bounded(s.roomSize, 2, 12, 5);
    s.roomDamping = bounded(s.roomDamping, 0, 1, .5f);
    s.roomDecay = bounded(s.roomDecay, .1f, 1.5f, .35f);
    const Settings defaults;
    for (int c = 0; c < 7; ++c) {
        s.azimuths[c] = wrapped(bounded(s.azimuths[c], -3600, 3600, defaults.azimuths[c]));
        s.elevations[c] = bounded(s.elevations[c], -90, 90, 0);
        s.distances[c] = bounded(s.distances[c], .5f, 5, 1);
    }
    for (int c = 0; c < 2; ++c) {
        s.stereoAzimuths[c] = wrapped(bounded(s.stereoAzimuths[c], -3600, 3600, defaults.stereoAzimuths[c]));
        s.stereoElevations[c] = bounded(s.stereoElevations[c], -90, 90, 0);
        s.stereoDistances[c] = bounded(s.stereoDistances[c], .5f, 5, 1);
    }
    return s;
}

// Inverse listener rotation in the common +X right, +Y up, -Z forward frame.
// Positive yaw means looking right; positive pitch means looking upwards.
// Rotating Cartesian vectors preserves the geometry near the poles.
void sceneDirections(const Settings& settings, float surroundMix,
                     std::array<float, 7>& azimuths, std::array<float, 7>& elevations) {
    const float yaw = settings.listenerYaw * kPi / 180;
    const float pitch = settings.listenerPitch * kPi / 180;
    for (int speaker = 0; speaker < 7; ++speaker) {
        float az = settings.azimuths[speaker], el = settings.elevations[speaker];
        if (speaker < 2) {
            az = settings.stereoAzimuths[speaker] + surroundMix * wrapped(az - settings.stereoAzimuths[speaker]);
            el = settings.stereoElevations[speaker] + surroundMix * (el - settings.stereoElevations[speaker]);
        }
        if (yaw == 0 && pitch == 0) {
            azimuths[speaker] = wrapped(az);
            elevations[speaker] = el;
            continue;
        }
        az *= kPi / 180; el *= kPi / 180;
        const float x = std::sin(az) * std::cos(el), y = std::sin(el), z = -std::cos(az) * std::cos(el);
        const float rx = std::cos(yaw) * x + std::sin(yaw) * z;
        const float rz = -std::sin(yaw) * x + std::cos(yaw) * z;
        const float ry = std::cos(pitch) * y + std::sin(pitch) * rz;
        const float rzz = -std::sin(pitch) * y + std::cos(pitch) * rz;
        azimuths[speaker] = std::atan2(rx, -rzz) * 180 / kPi;
        elevations[speaker] = std::asin(bounded(ry, -1, 1, 0)) * 180 / kPi;
    }
}
}

class Engine::Impl {
public:
    explicit Impl(int rate, const std::string& sofaPath, int renderer, int upmixer, const Settings& initial)
        : sampleRate(rate), rendererId(renderer), upmixerId(upmixer), dspDelay(1024) {
        if (rate < 8000 || rate > 96000) throw std::invalid_argument("Unsupported sample rate (8000..96000 Hz)");
        if (renderer < 0 || renderer > 2 || upmixer < 0 || upmixer > 2) throw std::invalid_argument("Unknown renderer/upmixer");
        if (renderer == 1 && !sofaPath.empty()) throw std::invalid_argument("OpenAL uses its builtin HRTF, not SOFA");
        dryDelay.resize(size_t(dspDelay) * 8);
        // PAE stays warm to permit latency-aligned live selection of ambient width.
        pae.reset(new PaeUpmixer(rate));
        advancedWidth.reset(new AdvancedWideners(rate));
        room.reset(new VirtualRoom(rate));
        if (upmixer == 2) ffmpegUpmix.reset(new FfmpegUpmixer(rate));
        // All graph construction stays outside process(). Small fixed presets
        // can then be selected live without pausing/repreparing the player.
        for (int i = 0; i < 3; ++i) ffmpegWidth[i].reset(new FfmpegWidener(rate, i + 1));
        for (int i = 0; i < 2; ++i) decorrelationWidth[i].reset(new StereoWidener(rate, i));
        if (renderer == 1) {
            openal.reset(new OpenAlRenderer(rate));
            rendererDelay = openal->latencyFrames();
            postDelay.resize(size_t(rendererDelay) * 7);
        }
        if (renderer == 2) {
            const auto positions = sanitized(initial);
            std::array<float, 7> az{}, el{};
            sceneDirections(positions, positions.mode == 2 ? 1 : 0, az, el);
            sofa.reset(new SofaRenderer(rate, sofaPath, az, el));
        }
        decoder.Init(cs_7point1, kBlock, rate);
        decoder.set_bass_redirection(false);
        const float seconds = 0.05f;
        smooth = 1 - std::exp(-1.0f / (rate * seconds));
        positionSmooth = 1 - std::exp(-float(kBlock) / (rate * 0.08f));
        bassSmooth = 1 - std::exp(-2 * kPi * 120 / rate);
        airSmooth = 1 - std::exp(-2 * kPi * std::min(6000.0f, rate * .45f) / rate);
        limiterRelease = 1 - std::exp(-1.0f / (rate * 0.08f));
        try {
            if (renderer == 0) {
            std::lock_guard<std::mutex> guard(steamLifecycleMutex);
            IPLContextSettings contextSettings{};
            contextSettings.version = STEAMAUDIO_VERSION;
            contextSettings.simdLevel = IPL_SIMDLEVEL_SSE2;
            checked(iplContextCreate(&contextSettings, &context), "context creation");
            IPLAudioSettings audio{rate, kHrtfBlock};
            IPLHRTFSettings hrtfSettings{};
            hrtfSettings.type = sofaPath.empty() ? IPL_HRTFTYPE_DEFAULT : IPL_HRTFTYPE_SOFA;
            hrtfSettings.sofaFileName = sofaPath.empty() ? nullptr : sofaPath.c_str();
            hrtfSettings.volume = 1;
            hrtfSettings.normType = IPL_HRTFNORMTYPE_RMS;
            checked(iplHRTFCreate(context, &audio, &hrtfSettings, &hrtf),
                    sofaPath.empty() ? "default HRTF creation" : "SOFA HRTF loading; check the file and SimpleFreeFieldHRIR compatibility");
            IPLBinauralEffectSettings effectSettings{hrtf};
            for (auto& effect : effects)
                checked(iplBinauralEffectCreate(context, &audio, &effectSettings, &effect), "binaural effect creation");
            }
        } catch (...) {
            releaseSteam();
            throw;
        }
        reset(false);
    }

    ~Impl() { releaseSteam(); }

    void releaseSteam() {
        std::lock_guard<std::mutex> guard(steamLifecycleMutex);
        for (auto& effect : effects) if (effect) iplBinauralEffectRelease(&effect);
        if (hrtf) iplHRTFRelease(&hrtf);
        if (context) iplContextRelease(&context);
    }

    void reset(bool filters = true) {
        decoder.flush();
        for (auto effect : effects) if (effect) iplBinauralEffectReset(effect);
        if (filters) {
            if (pae) pae->reset();
            if (ffmpegUpmix) ffmpegUpmix->reset();
            if (openal) openal->reset();
            if (sofa) sofa->reset();
            for (auto& widener : ffmpegWidth) widener->reset();
        }
        for (auto& widener : decorrelationWidth) widener->reset();
        advancedWidth->reset(); room->reset();
        surroundAlign.fill(0); surroundAlignIndex = 0;
        activeWidth.fill(false);
        input.fill(0);
        std::fill(dryDelay.begin(), dryDelay.end(), 0);
        std::fill(postDelay.begin(), postDelay.end(), 0);
        pendingFrames = 0;
        delayIndex = 0;
        postIndex = 0;
        channels = 0;
        firstSettings = true;
        firstPositions = true;
        bassSide = 0;
        airState.fill(0);
        limiter = 1;
        queue.assign(kBlock * 2, 0.0f);
    }

    std::vector<float> process(const float* samples, int frames, int count, const Settings& settings) {
        if (count != 1 && count != 2 && count != 6 && count != 8)
            throw std::invalid_argument("PCM must have 1, 2, 6 or 8 WAVE-order channels");
        if (frames < 0 || frames > 1048576 || (frames > 0 && !samples))
            throw std::invalid_argument("Invalid PCM frame count");
        if (channels != 0 && channels != count)
            throw std::invalid_argument("Reset the engine before changing channel count");
        channels = count;
        const auto target = sanitized(settings);
        if (firstSettings && frames) {
            current = target;
            virtualStereoWeight = target.virtualStereo ? 1 : 0;
            calibrationLeft = std::pow(10.0f, (target.hrtfGainDb - target.hrtfBalanceDb * .5f) / 20);
            calibrationRight = std::pow(10.0f, (target.hrtfGainDb + target.hrtfBalanceDb * .5f) / 20);
            for (int mode = 0; mode < 3; ++mode) modeWeights[mode] = mode == target.mode ? 1 : 0;
            for (int algorithm = 0; algorithm < 11; ++algorithm)
                widthWeights[algorithm] = algorithm == target.wideningAlgorithm ? 1 : 0;
            firstSettings = false;
        }
        std::vector<float> output(size_t(frames) * 2);
        for (int f = 0; f < frames; ++f) {
            float* dst = input.data() + pendingFrames * 8;
            std::fill(dst, dst + 8, 0);
            for (int c = 0; c < count; ++c) dst[c] = safeSample(samples[f * count + c]);
            if (count == 1) dst[1] = dst[0];
            if (++pendingFrames == kBlock) {
                render(target);
                pendingFrames = 0;
            }
            output[f * 2] = queue.front(); queue.pop_front();
            output[f * 2 + 1] = queue.front(); queue.pop_front();
        }
        return output;
    }

    void render(const Settings& target) {
        // Dry and ready multichannel PCM are aligned to the selected upmixer.
        // The external block FIFO adds another full block.
        std::array<float, kBlock * 8> delayed{};
        std::array<float, kBlock * 8> surround{};
        std::array<float, kBlock * 2> stereo{};
        for (int f = 0; f < kBlock; ++f) {
            for (int c = 0; c < 8; ++c) {
                delayed[f * 8 + c] = dryDelay[delayIndex * 8 + c];
                dryDelay[delayIndex * 8 + c] = input[f * 8 + c];
            }
            delayIndex = (delayIndex + 1) % dspDelay;
            stereo[f * 2] = input[f * 8];
            stereo[f * 2 + 1] = input[f * 8 + 1];
        }
        // Diagnostic P/A components have 1024 frames of delay. Align every
        // renderer/upmixer and direct path to that same delay for live switching.
        // For ready multichannel input, estimate ambience from its stereo fold.
        // Leave LFE outside this estimate: it remains in the dry fold, and live
        // LFE level changes cannot colour the delayed ambience subtraction.
        auto paeInput = stereo;
        if (channels > 2) for (int f = 0; f < kBlock; ++f) {
            const float* v = input.data() + f * 8;
            const float common = v[2] * kSqrtHalf;
            paeInput[f*2] = .5f * (v[0] + common + .5f*(v[4]+v[6]));
            paeInput[f*2+1] = .5f * (v[1] + common + .5f*(v[5]+v[7]));
        }
        pae->process(paeInput.data(), surround.data(), kBlock, paeDiagnostics.data());
        if (channels <= 2 && upmixerId != 1) {
            if (ffmpegUpmix) ffmpegUpmix->process(stereo.data(), surround.data(), kBlock);
            else {
                const float* decoded = decoder.decode(stereo.data());
                for (int f = 0; f < kBlock; ++f)
                    for (int c = 0; c < 8; ++c)
                        surround[f * 8 + c] = safeSample(decoded[f * 8 + kWaveFromFreeSurround[c]]);
            }
            // FreeSurround and FFmpeg have 512 frames of native delay.
            for (int f = 0; f < kBlock; ++f) {
                for (int c = 0; c < 8; ++c)
                    std::swap(surround[f*8+c], surroundAlign[surroundAlignIndex*8+c]);
                surroundAlignIndex = (surroundAlignIndex + 1) % kHalf;
            }
        } else if (channels > 2) surround = delayed;

        // The wideners see the same aligned stereo/downmix as the legacy M/S
        // branch, before either direct playback or two-source HRTF rendering.
        float downmixLfe = current.lfeGain;
        for (int f = 0; f < kBlock; ++f) {
            downmixLfe += smooth * (target.lfeGain - downmixLfe);
            const float* wave = delayed.data() + f * 8;
            float left = wave[0], right = wave[1];
            if (channels > 2) {
                const float common = (wave[2] + wave[3] * downmixLfe) * kSqrtHalf;
                left = .5f * (left + common + .5f * (wave[4] + wave[6]));
                right = .5f * (right + common + .5f * (wave[5] + wave[7]));
            }
            widthInput[f * 2] = left;
            widthInput[f * 2 + 1] = right;
        }
        const bool selectedNew = target.wideningAlgorithm != 0 ||
            std::any_of(widthWeights.begin() + 1, widthWeights.end(), [](float weight) { return weight > 1e-5f; });
        const bool processWidth = selectedNew && (target.mode == 1 || modeWeights[1] > 1e-5f);
        for (int algorithm = 1; algorithm < 11; ++algorithm) {
            const bool active = processWidth && (algorithm == target.wideningAlgorithm || widthWeights[algorithm] > 1e-5f);
            if (!active) {
                activeWidth[algorithm] = false;
                widthOutputs[algorithm - 1] = widthInput;
                continue;
            }
            if (!activeWidth[algorithm]) {
                // Clear only the newly activated effect. Other histories must
                // survive the crossfade. No graph allocation on the audio thread.
                if (algorithm <= 3) {
                    const std::array<float, kBlock * 2> silence{};
                    for (int block = 0; block < 2; ++block)
                        ffmpegWidth[algorithm - 1]->process(silence.data(), widthOutputs[algorithm - 1].data(), kBlock);
                } else if (algorithm <= 5) decorrelationWidth[algorithm - 4]->reset();
                else if (algorithm != 7) advancedWidth->reset(algorithm);
            }
            activeWidth[algorithm] = true;
            if (algorithm <= 3) {
                ffmpegWidth[algorithm - 1]->process(widthInput.data(), widthOutputs[algorithm - 1].data(), kBlock);
                continue;
            }
            if (algorithm <= 5) {
                decorrelationWidth[algorithm - 4]->process(widthInput.data(), widthOutputs[algorithm - 1].data(), kBlock,
                    target.widenerLow, target.widenerHigh, target.widenerCrossover,
                    target.transientProtection, target.widenerAmplitudePreserving);
                continue;
            }
            if (algorithm == 7) continue; // PAE is calculated below from the aligned diagnostics.
            AdvancedWidthSettings advanced;
            advanced.bandWidths = {{target.bandLowWidth, target.bandMidWidth, target.bandHighWidth}};
            advanced.lowCrossover = target.bandLowCrossover; advanced.highCrossover = target.bandHighCrossover;
            advanced.correlationGuard = target.correlationGuard;
            advanced.sideAmount = target.sideSynthesisAmount; advanced.sideHighpass = target.sideHighpass;
            advanced.shuffleAmount = target.shuffleAmount; advanced.shuffleFrequency = target.shuffleFrequency;
            advanced.microCents = target.microCents; advanced.microDelayMs = target.microDelayMs;
            advancedWidth->process(widthInput.data(), widthOutputs[algorithm-1].data(), kBlock, algorithm, advanced);
        }

        // Both channel layouts share the same seven renderer nodes. The stereo
        // pair uses only FL/FR; source and mode weights fade before convolution
        // so switching never resets the HRTF histories or doubles renderer cost.
        for (int c = 0; c < 7; ++c) {
            current.azimuths[c] = wrapped(current.azimuths[c] + positionSmooth * wrapped(target.azimuths[c] - current.azimuths[c]));
            current.elevations[c] += positionSmooth * (target.elevations[c] - current.elevations[c]);
        }
        for (int c = 0; c < 2; ++c) {
            current.stereoAzimuths[c] = wrapped(current.stereoAzimuths[c] + positionSmooth * wrapped(target.stereoAzimuths[c] - current.stereoAzimuths[c]));
            current.stereoElevations[c] += positionSmooth * (target.stereoElevations[c] - current.stereoElevations[c]);
        }
        current.listenerYaw = wrapped(current.listenerYaw + positionSmooth * wrapped(target.listenerYaw - current.listenerYaw));
        current.listenerPitch += positionSmooth * (target.listenerPitch - current.listenerPitch);
        std::array<float, 7> azimuths{}, elevations{}, panLeft{}, panRight{};
        sceneDirections(current, modeWeights[2], azimuths, elevations);
        for (int c = 0; c < 7; ++c) {
            const float x = std::sin(azimuths[c] * kPi / 180) * std::cos(elevations[c] * kPi / 180);
            panLeft[c] = std::sqrt(std::max(0.0f, .5f * (1 - x)));
            panRight[c] = std::sqrt(std::max(0.0f, .5f * (1 + x)));
        }
        if (firstPositions) {
            panStateLeft = panLeft;
            panStateRight = panRight;
            firstPositions = false;
        }

        std::array<float, kBlock * 8> mains{};
        std::array<float, kBlock * 2> spatial{}, direct{}, panned{};
        std::array<float, kBlock> lfeOutput{}, blends{}, leftCalibration{}, rightCalibration{};
        const float wantedLeft = std::pow(10.0f, (target.hrtfGainDb - target.hrtfBalanceDb * .5f) / 20);
        const float wantedRight = std::pow(10.0f, (target.hrtfGainDb + target.hrtfBalanceDb * .5f) / 20);
        for (int f = 0; f < kBlock; ++f) {
            current.surroundGain += smooth * (target.surroundGain - current.surroundGain);
            current.lfeGain += smooth * (target.lfeGain - current.lfeGain);
            current.width += smooth * (target.width - current.width);
            current.wideningAmount += smooth * (target.wideningAmount - current.wideningAmount);
            current.paeAmbientWidth += smooth * (target.paeAmbientWidth - current.paeAmbientWidth);
            current.paeAmbienceGainDb += smooth * (target.paeAmbienceGainDb - current.paeAmbienceGainDb);
            for (int algorithm = 0; algorithm < 11; ++algorithm)
                widthWeights[algorithm] += smooth * ((algorithm == target.wideningAlgorithm ? 1.f : 0.f) - widthWeights[algorithm]);
            current.hrtfBlend += smooth * (target.hrtfBlend - current.hrtfBlend);
            virtualStereoWeight += smooth * ((target.virtualStereo ? 1.0f : 0.0f) - virtualStereoWeight);
            calibrationLeft += smooth * (wantedLeft - calibrationLeft);
            calibrationRight += smooth * (wantedRight - calibrationRight);
            blends[f] = current.hrtfBlend;
            leftCalibration[f] = calibrationLeft;
            rightCalibration[f] = calibrationRight;
            for (int mode = 0; mode < 3; ++mode)
                modeWeights[mode] += smooth * ((mode == target.mode ? 1.0f : 0.0f) - modeWeights[mode]);
            for (int c = 0; c < 7; ++c)
                current.distances[c] += smooth * (target.distances[c] - current.distances[c]);
            for (int c = 0; c < 2; ++c)
                current.stereoDistances[c] += smooth * (target.stereoDistances[c] - current.stereoDistances[c]);

            const float* wave = delayed.data() + f * 8;
            float dryL = wave[0], dryR = wave[1];
            if (channels > 2) {
                const float center = wave[2] * kSqrtHalf;
                const float lfe = wave[3] * current.lfeGain * kSqrtHalf;
                dryL = .5f * (dryL + center + lfe + .5f * (wave[4] + wave[6]));
                dryR = .5f * (dryR + center + lfe + .5f * (wave[5] + wave[7]));
            }
            const float mid = .5f * (dryL + dryR);
            const float side = .5f * (dryL - dryR);
            bassSide += bassSmooth * (side - bassSide);
            // Protect LF Side below 120 Hz. Widening happens before the two
            // mono sources are independently positioned and HRTF-filtered.
            const float wide = side + (current.width - 1) * (side - bassSide);
            const float ambientL = paeDiagnostics[f*4+2], ambientR = paeDiagnostics[f*4+3];
            const float ambientMid = .5f*(ambientL+ambientR);
            const float ambientSide = .5f*(ambientL-ambientR) * current.paeAmbientWidth;
            const float ambientGain = std::pow(10.f, current.paeAmbienceGainDb / 20.f);
            // Preserve primary and the exact dry identity for width=1/gain=0dB.
            widthOutputs[6][f*2] = dryL + ambientGain*(ambientMid+ambientSide) - ambientL;
            widthOutputs[6][f*2+1] = dryR + ambientGain*(ambientMid-ambientSide) - ambientR;
            float widenedL = widthWeights[0] * (mid + wide);
            float widenedR = widthWeights[0] * (mid - wide);
            for (int algorithm = 1; algorithm < 11; ++algorithm) {
                widenedL += widthWeights[algorithm] * (dryL + current.wideningAmount * (widthOutputs[algorithm - 1][f * 2] - dryL));
                widenedR += widthWeights[algorithm] * (dryR + current.wideningAmount * (widthOutputs[algorithm - 1][f * 2 + 1] - dryR));
            }
            const float stereoSources[] = {
                modeWeights[0] * dryL + modeWeights[1] * widenedL,
                modeWeights[0] * dryR + modeWeights[1] * widenedR
            };
            direct[f * 2] = stereoSources[0] * (1 - virtualStereoWeight);
            direct[f * 2 + 1] = stereoSources[1] * (1 - virtualStereoWeight);
            // The reserved 6 dB applies to the seven-source sum, not the
            // two-source stereo pair. LFE remains one unpositioned feed.
            lfeOutput[f] = .5f * modeWeights[2] * surround[f * 8 + 3] * current.lfeGain * kSqrtHalf;
            for (int c = 0; c < 7; ++c) {
                const int waveChannel = kSpeakerWaveChannels[c];
                float sample = .5f * modeWeights[2] * surround[f * 8 + waveChannel] * (c >= 3 ? current.surroundGain : 1);
                roomInput[f*7+c] = sample + (c < 2 ? stereoSources[c] : 0);
                float distance = current.distances[c];
                if (c < 2) {
                    sample += virtualStereoWeight * stereoSources[c];
                    distance = current.stereoDistances[c] + modeWeights[2] * (distance - current.stereoDistances[c]);
                }
                // A deliberately modest distance approximation: 1/r level,
                // with progressive HF loss beyond the 1 m reference. No
                // synthesized room, time of flight, or near-field HRIR claims.
                airState[c] += airSmooth * (sample - airState[c]);
                const float airLoss = std::max(0.0f, distance - 1) * .15f;
                sample = (sample + airLoss * (airState[c] - sample)) / distance;
                mains[f * 8 + waveChannel] = sample;
                panStateLeft[c] += smooth * (panLeft[c] - panStateLeft[c]);
                panStateRight[c] += smooth * (panRight[c] - panStateRight[c]);
                panned[f * 2] += sample * panStateLeft[c];
                panned[f * 2 + 1] += sample * panStateRight[c];
            }
        }

        RoomSettings roomSettings;
        roomSettings.activeSources = modeWeights[2] > 1e-5f ? 7 : 2;
        roomSettings.enabled = target.roomEnabled; roomSettings.mix = target.roomMix;
        roomSettings.size = target.roomSize; roomSettings.damping = target.roomDamping;
        roomSettings.decay = target.roomDecay; roomSettings.yaw = current.listenerYaw;
        roomSettings.pitch = current.listenerPitch;
        auto world = current; world.listenerYaw = world.listenerPitch = 0;
        sceneDirections(world, modeWeights[2], roomSettings.azimuths, roomSettings.elevations);
        roomSettings.distances = current.distances;
        for (int c = 0; c < 2; ++c)
            roomSettings.distances[c] = current.stereoDistances[c] + modeWeights[2]*(current.distances[c]-current.stereoDistances[c]);
        room->process(roomInput.data(), roomWet.data(), kBlock, roomSettings);

        if (openal) openal->render(mains.data(), spatial.data(), kBlock, azimuths, elevations);
        else if (sofa) sofa->render(mains.data(), spatial.data(), kBlock, azimuths, elevations);
        else for (int offset = 0; offset < kBlock; offset += kHrtfBlock) {
            std::array<float, kHrtfBlock> mono{}, left{}, right{};
            float* inChannels[] = {mono.data()};
            float* outChannels[] = {left.data(), right.data()};
            IPLAudioBuffer in{1, kHrtfBlock, inChannels};
            IPLAudioBuffer out{2, kHrtfBlock, outChannels};
            for (int speaker = 0; speaker < 7; ++speaker) {
                const float az = azimuths[speaker] * kPi / 180;
                const float el = elevations[speaker] * kPi / 180;
                for (int n = 0; n < kHrtfBlock; ++n)
                    mono[n] = mains[(offset + n) * 8 + kSpeakerWaveChannels[speaker]];
                const bool silent = std::all_of(mono.begin(), mono.end(), [](float v) { return v == 0; });
                if (silent && iplBinauralEffectGetTailSize(effects[speaker]) == 0) continue;
                IPLBinauralEffectParams params{};
                params.direction = {std::sin(az) * std::cos(el), std::sin(el), -std::cos(az) * std::cos(el)};
                params.interpolation = IPL_HRTFINTERPOLATION_BILINEAR;
                params.spatialBlend = 1;
                params.hrtf = hrtf;
                if (silent) iplBinauralEffectGetTail(effects[speaker], &out);
                else iplBinauralEffectApply(effects[speaker], &params, &in, &out);
                for (int n = 0; n < kHrtfBlock; ++n) {
                    spatial[(offset + n) * 2] += safeSample(left[n]);
                    spatial[(offset + n) * 2 + 1] += safeSample(right[n]);
                }
            }
        }
        for (int f = 0; f < kBlock; ++f) {
            float values[] = {direct[f * 2], direct[f * 2 + 1], panned[f * 2], panned[f * 2 + 1], lfeOutput[f], roomWet[f*2], roomWet[f*2+1]};
            if (rendererDelay) {
                for (int c = 0; c < 7; ++c) std::swap(values[c], postDelay[postIndex * 7 + c]);
                postIndex = (postIndex + 1) % rendererDelay;
            }
            const float blend = blends[f];
            const float virtualL = blend * spatial[f * 2] + (1 - blend) * values[2] + values[4] + values[5];
            const float virtualR = blend * spatial[f * 2 + 1] + (1 - blend) * values[3] + values[4] + values[6];
            float left = safeSample(values[0] + virtualL * leftCalibration[f]);
            float right = safeSample(values[1] + virtualR * rightCalibration[f]);
            const float peak = std::max(std::abs(left), std::abs(right));
            const float wanted = peak > .98f ? .98f / peak : 1;
            limiter = wanted < limiter ? wanted : limiter + limiterRelease * (wanted - limiter);
            queue.push_back(left * limiter);
            queue.push_back(right * limiter);
        }
    }

    int sampleRate, rendererId, upmixerId, dspDelay, rendererDelay = 0, postIndex = 0;
    std::unique_ptr<PaeUpmixer> pae;
    std::unique_ptr<FfmpegUpmixer> ffmpegUpmix;
    std::unique_ptr<OpenAlRenderer> openal;
    std::unique_ptr<SofaRenderer> sofa;
    std::array<std::unique_ptr<FfmpegWidener>, 3> ffmpegWidth;
    std::array<std::unique_ptr<StereoWidener>, 2> decorrelationWidth;
    std::array<float, kBlock * 2> widthInput{};
    std::unique_ptr<AdvancedWideners> advancedWidth;
    std::unique_ptr<VirtualRoom> room;
    std::array<float, kBlock * 7> roomInput{};
    std::array<float, kBlock * 2> roomWet{};
    std::array<float, kBlock * 4> paeDiagnostics{};
    std::array<float, kHalf * 8> surroundAlign{};
    int surroundAlignIndex = 0;
    std::array<std::array<float, kBlock * 2>, 10> widthOutputs{};
    std::array<float, 11> widthWeights{};
    std::array<bool, 11> activeWidth{};
    DPL2FSDecoder decoder;
    IPLContext context = nullptr;
    IPLHRTF hrtf = nullptr;
    std::array<IPLBinauralEffect, 7> effects{};
    std::array<float, kBlock * 8> input{};
    std::vector<float> dryDelay, postDelay;
    std::deque<float> queue;
    int pendingFrames = 0, delayIndex = 0, channels = 0;
    bool firstSettings = true, firstPositions = true;
    Settings current;
    std::array<float, 3> modeWeights{};
    float smooth = 0, positionSmooth = 0, bassSmooth = 0, airSmooth = 0, limiterRelease = 0;
    std::array<float, 7> airState{};
    std::array<float, 7> panStateLeft{}, panStateRight{};
    float virtualStereoWeight = 0, calibrationLeft = 1, calibrationRight = 1;
    float bassSide = 0, limiter = 1;
};

Engine::Engine(int sampleRate, const std::string& sofaPath, int renderer, int upmixer, const Settings& initial)
    : impl_(new Impl(sampleRate, sofaPath, renderer, upmixer, initial)) {}
Engine::~Engine() = default;
int Engine::latencyFrames() const { return kBlock + impl_->dspDelay + impl_->rendererDelay; }
std::vector<float> Engine::process(const float* input, int frames, int channels, const Settings& settings) {
    return impl_->process(input, frames, channels, settings);
}
void Engine::reset() { impl_->reset(); }
}
