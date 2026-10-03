/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once

#include <cstddef>
#include <memory>

namespace spatial {

// JUCE-free streaming adaptation of Orchisama Das's CC0 StereoWidener.
// See STEREOWIDENER_PORT.md for the pinned source and deliberate corrections.
class StereoWidener {
public:
    // algorithm: 0 = 200-section allpass, 1 = optimized velvet-noise FIR.
    explicit StereoWidener(int sampleRate, int algorithm = 0);
    ~StereoWidener();
    StereoWidener(const StereoWidener&) = delete;
    StereoWidener& operator=(const StereoWidener&) = delete;

    // Interleaved L/R; arbitrary frame counts, including zero; in-place allowed.
    // Widths map 0..1 to the upstream equal-power dry/decorrelated angle 0..pi/2.
    // Cutoff is clamped to 200..min(4000, 0.45*sampleRate) Hz. Parameters ramp
    // sample-wise. Both widths zero select exact dry after the ramp settles.
    // No allocation, file access, locks, or shared state during processing.
    // No additional buffering latency; decorrelator/filter tails are effects.
    void process(const float* input, float* output, std::size_t frames,
                 float lowAmount, float highAmount, float crossoverHz,
                 bool transientProtection, bool amplitudePreserving = false);
    void reset();

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace spatial
