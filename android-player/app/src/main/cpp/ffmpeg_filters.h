/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once
#include <array>
#include <memory>
#include <string>

namespace spatial {
// libavfilter upmixer: exactly 1024 frames per call, WAVE order.
// surround's 1024/Hann/50% WOLA has 512 frames of algorithmic delay.
class FfmpegUpmixer {
public:
    explicit FfmpegUpmixer(int rate);
    ~FfmpegUpmixer();
    void process(const float* stereo, float* wave71, int frames);
    void reset();
private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

// Fixed libavfilter presets:
// 1 = extrastereo (M/S difference x2, unclipped),
// 2 = stereowiden (12 ms, negative crossfeed/echo .15, dry gain 1),
// 3 = haas (MID-derived stereo, 2.05/2.12 ms, side gain .35).
// The caller smooths dry/wet and limits peaks. Haas at 100% wet replaces SIDE
// with delayed MID. No file/network inputs or external filter expressions.
// Exactly 1024 stereo frames in/out; zero added transport latency. Delayed
// components have finite tails (12 ms / 2.12 ms).
class FfmpegWidener {
public:
    FfmpegWidener(int rate, int algorithm);
    ~FfmpegWidener();
    void process(const float* stereo, float* output, int frames);
    void reset();
private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

// Renders the seven mains; the caller mixes and controls LFE separately.
// Direction updates prepare a new filter on one background worker. The old
// filter keeps playing, then both receive two warm-up blocks before a 50 ms
// crossfade. Only the latest requested layout is queued, with two graphs max.
// Directions use the filter's integer-degree resolution; poles clamp to +/-89.
class SofaRenderer {
public:
    SofaRenderer(int rate, const std::string& sofa, const std::array<float, 7>& azimuths,
                 const std::array<float, 7>& elevations);
    ~SofaRenderer();
    void render(const float* wave71, float* stereo, int frames);
    void render(const float* wave71, float* stereo, int frames,
                const std::array<float, 7>& azimuths, const std::array<float, 7>& elevations);
    void reset();
    // Diagnostics/test synchronization only: never wait from the audio thread.
    // Returns when the current preparation finishes (possibly an intermediate
    // layout); rendering must continue to consume and finish pending transitions.
    bool waitForPreparation(int timeoutMs);
    bool isUpdating() const;
    std::string lastUpdateError() const;
private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};
}
