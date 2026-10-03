/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "advanced_wideners.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>

namespace spatial {
namespace {
constexpr double kPi = 3.1415926535897932384626433832795;
constexpr std::size_t kPitchBuffer = 8192; // >60 ms at the maximum 96 kHz rate.
constexpr std::size_t kSideBuffer = 512;

int checkedRate(int rate) {
    if (rate < 8000 || rate > 96000)
        throw std::invalid_argument("AdvancedWideners rate must be 8000..96000");
    return rate;
}
double bounded(double value, double low, double high, double fallback) {
    return std::isfinite(value) ? std::max(low, std::min(high, value)) : fallback;
}
double clean(double value) { return std::abs(value) < 1e-30 ? 0.0 : value; }
double follow(double value, double target, double coefficient) {
    const double next = value + coefficient * (target - value);
    return std::abs(next - target) < 1e-10 ? target : next;
}

struct Lowpass {
    double value = 0;
    double coefficient = 0;
    void tune(double frequency, int rate) {
        coefficient = 1.0 - std::exp(-2.0 * kPi * frequency / rate);
    }
    double process(double input) {
        value = clean(value + coefficient * (input - value));
        return value;
    }
    void reset() { value = 0; }
};

struct Allpass {
    double coefficient = 0;
    double previousInput = 0, previousOutput = 0;
    double process(double input) {
        const double output = coefficient * input + previousInput - coefficient * previousOutput;
        previousInput = input;
        previousOutput = clean(output);
        return output;
    }
    void reset() { previousInput = previousOutput = 0; }
};

// PRNG is solely for fixed decorrelator design, never for security.
double uniform(std::uint32_t& seed) {
    seed ^= seed << 13;
    seed ^= seed >> 17;
    seed ^= seed << 5;
    return double(seed >> 8) / 16777216.0;
}

struct MultibandState {
    Lowpass low, high;
    std::array<double, 3> widths{{1, 1, 1}};
    double lowCut = 200, highCut = 3000, guardMix = 1;
    double midPower = 0, originalSidePower = 0, widenedSidePower = 0;
    double guardGain = 1;
    unsigned coefficientClock = 0;
    bool initialized = false;
};
struct SideState {
    std::array<std::array<Allpass, 6>, 2> allpass{};
    std::array<double, kSideBuffer> delay{};
    Lowpass bass;
    std::size_t position = 0;
    double amount = .5, cutoff = 200;
    unsigned coefficientClock = 0;
    bool initialized = false;
};
struct ShufflerState {
    Lowpass low;
    double amount = .5, cutoff = 700;
    unsigned coefficientClock = 0;
    bool initialized = false;
};
struct PitchState {
    std::array<std::array<double, kPitchBuffer>, 2> delay{};
    std::array<double, 2> phase{{0, 0}};
    std::size_t position = 0;
    double cents = 6, delayMs = 12;
    bool initialized = false;
};
} // namespace

class AdvancedWideners::Impl {
public:
    explicit Impl(int rate)
        : rate_(checkedRate(rate)), smoothing_(1 - std::exp(-1 / (.020 * rate_))),
          meter_(1 - std::exp(-1 / (.025 * rate_))),
          guardAttack_(1 - std::exp(-1 / (.005 * rate_))),
          guardRelease_(1 - std::exp(-1 / (.150 * rate_))),
          sideDelay_(std::size_t(std::lround(.0033 * rate_))) {
        // Log-spaced but independently perturbed frequencies make two stable,
        // deterministic phase responses. Coefficients remain inside the unit disk.
        std::uint32_t seed = 0x7357a91dU;
        for (auto& branch : side_.allpass) {
            for (std::size_t stage = 0; stage < branch.size(); ++stage) {
                const double nominal = 100 * std::pow(1.95, double(stage));
                const double frequency = std::min(.40 * rate_, nominal * (.7 + .6 * uniform(seed)));
                const double tangent = std::tan(kPi * frequency / rate_);
                branch[stage].coefficient = (tangent - 1) / (tangent + 1);
            }
        }
    }

    void reset(int algorithm = 0) {
        if (algorithm == 0 || algorithm == 6) multiband_ = MultibandState{};
        if (algorithm == 0 || algorithm == 8) {
        // Retain fixed allpass coefficients; clear only histories.
        for (auto& branch : side_.allpass) for (auto& section : branch) section.reset();
        side_.delay.fill(0);
        side_.bass.reset();
        side_.position = 0;
        side_.initialized = false;
        side_.coefficientClock = 0;
        }
        if (algorithm == 0 || algorithm == 9) shuffle_ = ShufflerState{};
        if (algorithm == 0 || algorithm == 10) {
        // Avoid a large temporary on the audio stack.
        for (auto& channel : pitch_.delay) channel.fill(0);
        pitch_.phase = {{0, 0}};
        pitch_.position = 0;
        pitch_.initialized = false;
        }
    }

    void process(const float* input, float* output, int frames, int algorithm,
                 const AdvancedWidthSettings& settings) {
        if (algorithm != 6 && algorithm != 8 && algorithm != 9 && algorithm != 10)
            throw std::invalid_argument("Unknown advanced widening algorithm");
        if (frames < 0 || (frames > 0 && (!input || !output)))
            throw std::invalid_argument("Invalid advanced widening buffer");
        if (frames == 0) return;
        switch (algorithm) {
            case 6: multiband(input, output, frames, settings); break;
            case 8: synthesizeSide(input, output, frames, settings); break;
            case 9: shuffler(input, output, frames, settings); break;
            case 10: microPitch(input, output, frames, settings); break;
        }
    }

private:
    void multiband(const float* input, float* output, int frames,
                   const AdvancedWidthSettings& settings) {
        auto& state = multiband_;
        std::array<double, 3> targetWidths{};
        for (std::size_t i = 0; i < 3; ++i)
            targetWidths[i] = bounded(settings.bandWidths[i], 0, 2, 1);
        const double targetLow = bounded(settings.lowCrossover, 40, 1200, 200);
        const double targetHigh = bounded(settings.highCrossover,
            std::max(800.0, targetLow * 1.25), std::min(12000.0, .45 * rate_),
            std::min(3000.0, .45 * rate_));
        const double targetGuard = settings.correlationGuard ? 1.0 : 0.0;
        if (!state.initialized) {
            state.widths = targetWidths;
            state.lowCut = targetLow; state.highCut = targetHigh;
            state.guardMix = targetGuard;
            state.initialized = true;
        }
        for (int frame = 0; frame < frames; ++frame) {
            for (std::size_t band = 0; band < 3; ++band)
                state.widths[band] = follow(state.widths[band], targetWidths[band], smoothing_);
            state.lowCut = follow(state.lowCut, targetLow, smoothing_);
            state.highCut = follow(state.highCut, targetHigh, smoothing_);
            state.guardMix = follow(state.guardMix, targetGuard, smoothing_);
            if ((state.coefficientClock++ & 15U) == 0) {
                state.low.tune(state.lowCut, rate_);
                state.high.tune(state.highCut, rate_);
            }
            const std::size_t at = std::size_t(frame) * 2;
            const double left = bounded(input[at], -16, 16, 0);
            const double right = bounded(input[at + 1], -16, 16, 0);
            const double mid = (left + right) * .5, side = (left - right) * .5;
            const double low = state.low.process(side), belowHigh = state.high.process(side);
            // Complementary residuals telescope exactly for unity widths.
            double widened = side + (state.widths[0] - 1) * low +
                (state.widths[1] - 1) * (belowHigh - low) +
                (state.widths[2] - 1) * (side - belowHigh);
            state.midPower = clean(state.midPower + meter_ * (mid * mid - state.midPower));
            state.originalSidePower = clean(state.originalSidePower + meter_ * (side * side - state.originalSidePower));
            state.widenedSidePower = clean(state.widenedSidePower + meter_ * (widened * widened - state.widenedSidePower));
            // Never force pre-existing anti-phase content to mono. For a normal
            // input this targets E(Sout)<=E(M); for a negative-correlation input
            // the original side energy is the ceiling, so widening won't add to it.
            const double ceiling = std::max(state.midPower, state.originalSidePower);
            const double desired = state.widenedSidePower > ceiling + 1e-16
                ? std::sqrt(ceiling / state.widenedSidePower) : 1.0;
            state.guardGain = follow(state.guardGain, desired,
                desired < state.guardGain ? guardAttack_ : guardRelease_);
            const bool unity = state.widths[0] == 1 && state.widths[1] == 1 && state.widths[2] == 1;
            if (!unity) widened *= 1 + state.guardMix * (state.guardGain - 1);
            output[at] = float(mid + widened);
            output[at + 1] = float(mid - widened);
        }
    }

    void synthesizeSide(const float* input, float* output, int frames,
                        const AdvancedWidthSettings& settings) {
        auto& state = side_;
        const double targetAmount = bounded(settings.sideAmount, 0, 1, .5);
        const double targetCutoff = bounded(settings.sideHighpass, 20, 2000, 200);
        if (!state.initialized) {
            state.amount = targetAmount; state.cutoff = targetCutoff;
            state.initialized = true;
        }
        for (int frame = 0; frame < frames; ++frame) {
            state.amount = follow(state.amount, targetAmount, smoothing_);
            state.cutoff = follow(state.cutoff, targetCutoff, smoothing_);
            if ((state.coefficientClock++ & 15U) == 0) state.bass.tune(state.cutoff, rate_);
            const std::size_t at = std::size_t(frame) * 2;
            const double left = bounded(input[at], -16, 16, 0);
            const double right = bounded(input[at + 1], -16, 16, 0);
            const double mid = (left + right) * .5;
            double a = mid, b = mid;
            for (auto& section : state.allpass[0]) a = section.process(a);
            for (auto& section : state.allpass[1]) b = section.process(b);
            state.delay[state.position] = b;
            const std::size_t read = (state.position + kSideBuffer - sideDelay_) % kSideBuffer;
            const double difference = (a - state.delay[read]) * .5;
            state.position = (state.position + 1) % kSideBuffer;
            const double addition = state.amount * (difference - state.bass.process(difference));
            output[at] = float(left + addition);
            output[at + 1] = float(right - addition);
        }
    }

    void shuffler(const float* input, float* output, int frames,
                  const AdvancedWidthSettings& settings) {
        auto& state = shuffle_;
        const double targetAmount = bounded(settings.shuffleAmount, -1, 1, .5);
        const double targetCutoff = bounded(settings.shuffleFrequency, 80, 3000, 700);
        if (!state.initialized) {
            state.amount = targetAmount; state.cutoff = targetCutoff;
            state.initialized = true;
        }
        for (int frame = 0; frame < frames; ++frame) {
            state.amount = follow(state.amount, targetAmount, smoothing_);
            state.cutoff = follow(state.cutoff, targetCutoff, smoothing_);
            if ((state.coefficientClock++ & 15U) == 0) state.low.tune(state.cutoff, rate_);
            const std::size_t at = std::size_t(frame) * 2;
            const double left = bounded(input[at], -16, 16, 0);
            const double right = bounded(input[at + 1], -16, 16, 0);
            const double mid = (left + right) * .5, side = (left - right) * .5;
            const double widened = side + state.amount * state.low.process(side);
            output[at] = float(mid + widened);
            output[at + 1] = float(mid - widened);
        }
    }

    double readPitch(int channel, double delay) const {
        double position = double(pitch_.position) - delay;
        if (position < 0) position += kPitchBuffer;
        const std::size_t before = std::size_t(position);
        const double fraction = position - double(before);
        const std::size_t after = (before + 1) % kPitchBuffer;
        return pitch_.delay[channel][before] * (1 - fraction) + pitch_.delay[channel][after] * fraction;
    }

    void microPitch(const float* input, float* output, int frames,
                    const AdvancedWidthSettings& settings) {
        auto& state = pitch_;
        const double targetCents = bounded(settings.microCents, 0, 20, 6);
        const double targetDelay = bounded(settings.microDelayMs, 1, 40, 12);
        if (!state.initialized) {
            state.cents = targetCents; state.delayMs = targetDelay;
            state.initialized = true;
        }
        for (int frame = 0; frame < frames; ++frame) {
            state.cents = follow(state.cents, targetCents, smoothing_);
            state.delayMs = follow(state.delayMs, targetDelay, smoothing_);
            const double center = state.delayMs * rate_ / 1000;
            // Read-head range stays positive even at the 1 ms minimum delay.
            const double span = std::min(.024 * rate_, center * 1.8);
            const double pitchBlend = std::min(1.0, state.cents / .25);
            const std::size_t at = std::size_t(frame) * 2;
            // Write BOTH inputs before outputs so exact in-place operation is safe.
            state.delay[0][state.position] = bounded(input[at], -16, 16, 0);
            state.delay[1][state.position] = bounded(input[at + 1], -16, 16, 0);
            for (int channel = 0; channel < 2; ++channel) {
                const double phase = state.phase[channel];
                double otherPhase = phase + .5;
                if (otherPhase >= 1) otherPhase -= 1;
                const double weight = .5 - .5 * std::cos(2 * kPi * phase);
                const double first = readPitch(channel, center + (phase - .5) * span);
                const double second = readPitch(channel, center + (otherPhase - .5) * span);
                const double shifted = weight * first + (1 - weight) * second;
                const double stationary = readPitch(channel, center);
                output[at + channel] = float(stationary + pitchBlend * (shifted - stationary));
                const double ratio = std::exp2((channel == 0 ? 1 : -1) * state.cents / 1200);
                double next = phase + (1 - ratio) / span;
                if (next < 0) next += 1;
                else if (next >= 1) next -= 1;
                state.phase[channel] = next;
            }
            state.position = (state.position + 1) % kPitchBuffer;
        }
    }

    const int rate_;
    const double smoothing_, meter_, guardAttack_, guardRelease_;
    const std::size_t sideDelay_;
    MultibandState multiband_;
    SideState side_;
    ShufflerState shuffle_;
    PitchState pitch_;
};

AdvancedWideners::AdvancedWideners(int sampleRate) : impl_(new Impl(sampleRate)) {}
AdvancedWideners::~AdvancedWideners() = default;
void AdvancedWideners::reset(int algorithm) { impl_->reset(algorithm); }
void AdvancedWideners::process(const float* input, float* output, int frames, int algorithm,
                              const AdvancedWidthSettings& settings) {
    impl_->process(input, output, frames, algorithm, settings);
}
} // namespace spatial
