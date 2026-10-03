/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "equalizer30.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <stdexcept>

namespace spatial {
namespace {
constexpr double pi = 3.14159265358979323846;
// Third-octave peaking sections; frequencies above Nyquist are bypassed.
constexpr double q = 4.318473046963146;
constexpr int controlFrames = 32;
}
const std::array<double, Equalizer30::kBands> Equalizer30::frequencies{{
    25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200,
    250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000,
    2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
}};
Equalizer30::Equalizer30(int sampleRate) : sampleRate_(sampleRate) {
    if (sampleRate < 8000 || sampleRate > 192000) throw std::invalid_argument("Unsupported EQ sample rate");
}

double Equalizer30::Filter::tick(double value, size_t channel) {
    const double output = b0 * value + z1[channel];
    z1[channel] = b1 * value - a1 * output + z2[channel];
    z2[channel] = b2 * value - a2 * output;
    // Avoid denormal arithmetic after long silent passages.
    if (std::abs(z1[channel]) < 1e-24) z1[channel] = 0;
    if (std::abs(z2[channel]) < 1e-24) z2[channel] = 0;
    return output;
}

Equalizer30::Filter Equalizer30::coefficients(size_t band, double gain) const {
    Filter result;
    if (gain == 0 || frequencies[band] >= sampleRate_ * .49) return result;
    const double omega = 2 * pi * frequencies[band] / sampleRate_;
    const double alpha = std::sin(omega) / (2 * q);
    const double a = std::pow(10., gain / 40.);
    const double divisor = 1 + alpha / a;
    result.b0 = (1 + alpha * a) / divisor;
    result.b1 = (-2 * std::cos(omega)) / divisor;
    result.b2 = (1 - alpha * a) / divisor;
    result.a1 = result.b1;
    result.a2 = (1 - alpha / a) / divisor;
    return result;
}

double Equalizer30::responseHeadroom(const std::array<float, kBands>& gains) const {
    // Measure the complete cascade, including overlap, rather than compensating
    // only the loudest slider. Fixed work, only when the user changes the curve.
    std::array<Filter, kBands> curve;
    bool boosts = false;
    for (size_t band = 0; band < kBands; ++band) {
        curve[band] = coefficients(band, gains[band]);
        boosts |= gains[band] > 0 && frequencies[band] < sampleRate_ * .49;
    }
    if (!boosts) return 0;
    double peak = 1;
    const double maxFrequency = sampleRate_ * .499;
    for (size_t point = 0; point < 512 + kBands; ++point) {
        const double frequency = point < 512
            ? 10 * std::pow(maxFrequency / 10, point / 511.)
            : frequencies[point - 512];
        if (frequency >= maxFrequency) continue;
        const auto z = std::polar(1., -2 * pi * frequency / sampleRate_);
        double magnitude = 1;
        for (const auto& filter : curve) {
            magnitude *= std::abs((filter.b0 + filter.b1*z + filter.b2*z*z)
                / (1. + filter.a1*z + filter.a2*z*z));
        }
        peak = std::max(peak, magnitude);
    }
    return 20 * std::log10(peak) + .5; // reserve for the response between probes
}

void Equalizer30::setGains(const std::array<float, kBands>& gainsDb) {
    for (float gain : gainsDb) if (!std::isfinite(gain) || gain < -12 || gain > 12)
        throw std::invalid_argument("EQ requires 30 finite gains in -12..12 dB");
    if (gainsDb == targets_) return;
    const double nextHeadroom = responseHeadroom(gainsDb);
    targetHeadroom_ = nextHeadroom;
    targets_ = gainsDb;
    flat_ = false;
    smoothingFrames_ = std::max(1, sampleRate_ / 25); // 40 ms parameter ramp
    updateCountdown_ = 0;
}

void Equalizer30::updateCoefficients() {
    const double step = smoothingFrames_ > 0
        ? std::min(1., controlFrames / static_cast<double>(smoothingFrames_)) : 1.;
    if (smoothingFrames_ == 0 && current_ == std::array<double, kBands>{}) {
        flat_ = true;
        for (auto& filter : filters_) filter = Filter{};
        return;
    }
    if (smoothingFrames_ == 0) return;
    bool any = false;
    for (size_t band = 0; band < kBands; ++band) {
        current_[band] += (targets_[band] - current_[band]) * step;
        const auto next = coefficients(band, current_[band]);
        auto& filter = filters_[band];
        filter.b0 = next.b0; filter.b1 = next.b1; filter.b2 = next.b2;
        filter.a1 = next.a1; filter.a2 = next.a2;
        any |= current_[band] != 0;
    }
    if (!any && smoothingFrames_ == 0) {
        flat_ = true;
        for (auto& filter : filters_) filter = Filter{};
    }
}

void Equalizer30::process(float* stereo, size_t frames) {
    if (flat_) return; // bit-identical Flat, no level change or unnecessary DSP
    for (size_t frame = 0; frame < frames; ++frame) {
        if (updateCountdown_ == 0) {
            updateCoefficients();
            updateCountdown_ = controlFrames;
        }
        --updateCountdown_;
        if (flat_) return;
        if (smoothingFrames_ > 0) {
            currentHeadroom_ += (targetHeadroom_ - currentHeadroom_) / smoothingFrames_;
            level_ = std::pow(10., -currentHeadroom_ / 20.);
            --smoothingFrames_;
        }
        for (size_t channel = 0; channel < 2; ++channel) {
            double value = std::isfinite(stereo[frame*2+channel]) ? stereo[frame*2+channel] : 0;
            value *= level_;
            for (auto& filter : filters_) value = filter.tick(value, channel);
            // Last-resort overshoot protection. Ordinary signals remain linear;
            // the existing PCM writer has the same full-scale safety boundary.
            stereo[frame*2+channel] = static_cast<float>(std::max(-1., std::min(1., value)));
        }
    }
}

void Equalizer30::reset() {
    for (auto& filter : filters_) { filter.z1 = {}; filter.z2 = {}; }
    current_ = {};
    currentHeadroom_ = 0;
    level_ = 1;
    smoothingFrames_ = std::max(1, sampleRate_ / 25);
    updateCountdown_ = 0;
    flat_ = std::all_of(targets_.begin(), targets_.end(), [](float value) { return value == 0; });
}
}
