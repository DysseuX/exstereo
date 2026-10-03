/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once

#include <array>
#include <cstddef>

namespace spatial {

/** Stereo third-octave EQ after binaural rendering and before PCM quantization. */
class Equalizer30 {
public:
    static constexpr size_t kBands = 30;
    static const std::array<double, kBands> frequencies;
    explicit Equalizer30(int sampleRate);
    void setGains(const std::array<float, kBands>& gainsDb);
    void process(float* interleavedStereo, size_t frames);
    void reset();
    double headroomDb() const { return targetHeadroom_; }

private:
    struct Filter {
        double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        std::array<double, 2> z1{}, z2{};
        double tick(double value, size_t channel);
    };
    Filter coefficients(size_t band, double gain) const;
    double responseHeadroom(const std::array<float, kBands>& gains) const;
    void updateCoefficients();
    int sampleRate_;
    int updateCountdown_ = 0;
    int smoothingFrames_ = 0;
    bool flat_ = true;
    double currentHeadroom_ = 0, targetHeadroom_ = 0;
    double level_ = 1;
    std::array<float, kBands> targets_{};
    std::array<double, kBands> current_{};
    std::array<Filter, kBands> filters_{};
};
}
