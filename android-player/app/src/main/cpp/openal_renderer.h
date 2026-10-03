/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once

#include <array>
#include <memory>
#include <string>

namespace spatial {

// Software-only OpenAL Soft loopback: WAVE 7.1 -> binaural stereo.
// Seven mono sources use FL FR FC BL BR SL SR positions, clockwise azimuth
// and upward elevation in degrees. LFE (WAVE channel 3) is not rendered.
// Caller supplies channel gains/headroom and mixes the equally delayed LFE.
// This renderer explicitly selects OpenAL's embedded HRTF; it does not load SOFA.
class OpenAlRenderer {
public:
    explicit OpenAlRenderer(int sampleRate);
    ~OpenAlRenderer();
    OpenAlRenderer(const OpenAlRenderer&) = delete;
    OpenAlRenderer& operator=(const OpenAlRenderer&) = delete;

    // Only streaming look-ahead buffering. HRTF propagation/filter delay is
    // intentionally not stripped. Feed this many zero frames at EOF and trim
    // the same initial number, as with the enclosing spatial Engine.
    int latencyFrames() const;
    const std::string& hrtfName() const;
    void render(const float* wave71, float* stereo, int frames,
                const std::array<float, 7>& azimuths,
                const std::array<float, 7>& elevations);
    void reset();

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};
}
