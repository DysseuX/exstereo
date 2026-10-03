/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once

#include <array>
#include <memory>

namespace spatial {

struct AdvancedWidthSettings {
    std::array<float, 3> bandWidths{{1.f, 1.25f, 1.5f}};
    float lowCrossover = 200.f;
    float highCrossover = 3000.f;
    bool correlationGuard = true;
    float sideAmount = .5f;
    float sideHighpass = 200.f;
    float shuffleAmount = .5f;
    float shuffleFrequency = 700.f;
    float microCents = 6.f;
    float microDelayMs = 12.f;
};

// Causal stereo effects; signal definitions and limits are in ADVANCED_WIDENERS.md.
class AdvancedWideners {
public:
    explicit AdvancedWideners(int sampleRate);
    ~AdvancedWideners();
    AdvancedWideners(const AdvancedWideners&) = delete;
    AdvancedWideners& operator=(const AdvancedWideners&) = delete;

    // Interleaved stereo, arbitrary nonnegative frame counts, in-place allowed.
    // algorithm: 6 multiband M/S; 8 Mid->Side; 9 shuffler; 10 MicroPitch.
    // Each algorithm has independent history, permitting one instance to render
    // all four branches for an external crossfade. Only the selected branch is
    // advanced. No allocation/locks/I/O in valid process calls; no framing delay.
    // PCM input is sanitized to finite +/-16. The engine's common limiter handles output peaks.
    void process(const float* input, float* output, int frames, int algorithm,
                 const AdvancedWidthSettings& settings);
    void reset(int algorithm = 0);

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace spatial
