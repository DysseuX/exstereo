/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once

#include <array>
#include <memory>
#include <string>
#include <vector>

namespace spatial {

struct Settings {
    // 0: plain stereo/downmix, 1: stereo width, 2: chosen upmix + binaural.
    int mode = 2;
    float width = 1.25f;
    float surroundGain = 0.5f;
    float lfeGain = 1.0f;
    // FL FR FC BL BR SL SR, clockwise from straight ahead; elevation upwards.
    std::array<float, 7> azimuths{{-30, 30, 0, -150, 150, -90, 90}};
    std::array<float, 7> elevations{{0, 0, 0, 0, 0, 0, 0}};
    std::array<float, 7> distances{{1, 1, 1, 1, 1, 1, 1}};
    // Direct stereo remains the native API default for legacy clients. When
    // enabled, modes 0/1 feed two independent mono HRTF sources, without upmix.
    bool virtualStereo = false;
    std::array<float, 2> stereoAzimuths{{-30, 30}};
    std::array<float, 2> stereoElevations{{0, 0}};
    std::array<float, 2> stereoDistances{{1, 1}};
    // Playback calibration, not a modification of the profile's anatomy.
    float hrtfBlend = 1;
    float listenerYaw = 0;
    float listenerPitch = 0;
    float hrtfBalanceDb = 0;
    float hrtfGainDb = 0;
    // 0 bass-protected M/S; 1..3 FFmpeg extra/stereowiden/haas;
    // 4..5 StereoWidener allpass/velvet-noise. Only applied in mode 1.
    int wideningAlgorithm = 0;
    float wideningAmount = .5f;
    float widenerLow = 0, widenerHigh = .5f, widenerCrossover = 1000;
    bool transientProtection = true, widenerAmplitudePreserving = false;
    // v3: multiband, ambient-only PAE, complementary Side, shuffler, MicroPitch.
    float bandLowWidth = 1, bandMidWidth = 1.25f, bandHighWidth = 1.5f;
    float bandLowCrossover = 200, bandHighCrossover = 3000;
    bool correlationGuard = true;
    float paeAmbientWidth = 1.5f, paeAmbienceGainDb = 0;
    float sideSynthesisAmount = .5f, sideHighpass = 200;
    float shuffleAmount = .5f, shuffleFrequency = 700;
    float microCents = 6, microDelayMs = 12;
    // Independent room send, before the final linked limiter. Room reflections
    // use an analytic binaural approximation, not measured BRIRs.
    bool roomEnabled = false;
    float roomMix = .15f, roomSize = 5, roomDamping = .5f, roomDecay = .35f;
};

// Float PCM uses WAVE ordering: FL FR FC LFE BL BR SL SR.
// A stream's channel count is fixed until reset(). Every process call returns
// exactly the supplied number of frames. Feed latencyFrames() zero frames at
// EOF, trim the same number at the beginning, then keep the original duration.
// This is buffering latency; the direction-dependent HRTF impulse remains part
// of the actual sound and is intentionally not independently time-aligned.
class Engine {
public:
    // Renderer: 0 Steam, 1 OpenAL builtin HRTF, 2 FFmpeg sofalizer.
    // Upmixer: 0 FreeSurround, 1 PAE/PAD, 2 FFmpeg surround.
    // All renderers accept live positions. Distance is a bounded gain/air-loss
    // approximation; no propagation delay or near-field HRTF is synthesized.
    explicit Engine(int sampleRate, const std::string& sofaPath = "", int renderer = 0,
                    int upmixer = 0, const Settings& initial = Settings{});
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;
    int latencyFrames() const;
    std::vector<float> process(const float* input, int frames, int channels, const Settings& settings);
    void reset();
private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};
}
