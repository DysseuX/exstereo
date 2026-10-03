/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

// Adapted from Orchisama Das, StereoWidener (CC0-1.0), commit
// 8964075a97e6aa3b2b5264b93fb2f84847581295. See STEREOWIDENER_PORT.md.
#include "stereo_widener.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>

namespace spatial {
namespace {
constexpr double kPi = 3.1415926535897932384626433832795;
constexpr int kSections = 200;
constexpr int kButterworthSections = 4; // Upstream C++ implementation uses order 8.
constexpr int kVelvetBuffer = 1441; // Last 48-kHz tap 672, scaled up to 96 kHz.

double finiteBound(double value, double low, double high, double fallback) {
    return std::isfinite(value) ? std::max(low, std::min(value, high)) : fallback;
}
double clean(double value) {
    return std::abs(value) < 1e-30 ? 0.0 : value;
}
int checkedRate(int rate) {
    if (rate < 8000 || rate > 96000)
        throw std::invalid_argument("StereoWidener sample rate must be 8000..96000");
    return rate;
}
int checkedAlgorithm(int algorithm) {
    if (algorithm < 0 || algorithm > 1)
        throw std::invalid_argument("Unknown StereoWidener algorithm");
    return algorithm;
}

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double z1 = 0, z2 = 0;
    double process(double x) {
        const double y = b0 * x + z1;
        z1 = clean(b1 * x - a1 * y + z2);
        z2 = clean(b2 * x - a2 * y);
        return y;
    }
    void clear() { z1 = z2 = 0; }
};

// Deterministic audio PRNG; distinct L/R seeds correct the upstream C++ decorrelator.
double uniform(std::uint32_t& state) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return double(state >> 8) / 16777216.0;
}

struct VelvetTap { int delay; double gain; };
// Exact nonzero values of Resources/opt_vn_filters.txt (two 720-sample rows),
// SHA256 94108d26fde650ac1a239d58ba22d8351ab161e00f035e5a4205f121a740a693.
// The upstream generation notebook specifies 48000 Hz. No resource parser runs
// on the phone. The constructor scales delays and normalizes each FIR's energy.
constexpr std::array<std::array<VelvetTap, 15>, 2> kVelvetTaps{{
    {{{0,1.0},{48,1.058},{96,-.5971},{144,.4181},{191,-.2201},
      {240,-.0441},{288,-.0358},{335,.0774},{384,-.032},{432,-.0071},
      {480,-.0039},{528,.0097},{576,.0019},{624,.0012},{672,-.0032}}},
    {{{0,-1.0},{48,.9928},{96,.553},{144,.4268},{192,-.0525},
      {239,.1981},{288,.1238},{336,-.0155},{383,.0339},{432,-.0053},
      {480,-.0039},{528,-.0042},{576,.0014},{624,-.0036},{672,-.0026}}}
}};

struct Channel {
    std::array<Biquad, kSections> allpass{};
    std::array<VelvetTap, 15> taps{};
    std::array<double, kVelvetBuffer> delay{};
    // [dry/decorrelated][low/high][section]. Keep both filterbanks warm so
    // changing their type can be crossfaded without stale states.
    std::array<std::array<std::array<Biquad, kButterworthSections>, 2>, 2> butter{};
    std::array<std::array<Biquad, 2>, 2> linkwitz{};
};

class TransientGate {
public:
    explicit TransientGate(int rate) : attack_(1 - std::exp(-1 / (.005 * rate))),
        release_(1 - std::exp(-1 / (.050 * rate))),
        holdSamples_(int(.080 * rate)), inhibitSamples_(int(.050 * rate)),
        fadeSamples_(std::max(1, int(.010 * rate))) {}

    double process(double left, double right) {
        const double peak = std::max(std::abs(left), std::abs(right));
        const double before = envelope_;
        envelope_ = clean(envelope_ + (peak > envelope_ ? attack_ : release_) * (peak - envelope_));
        second_ = previous_;
        previous_ = current_;
        current_ = envelope_;
        if (samples_ < 0xffffffffULL) ++samples_;
        mean_ += (envelope_ - mean_) / double(samples_);
        const bool localPeak = previous_ > second_ && previous_ > current_;
        const double forget = localPeak ? .01 : .99;
        threshold_ = forget * threshold_ + (1 - forget) * 4 * mean_;
        const bool rising = previous_ > second_ && current_ > previous_ &&
                            previous_ > std::max(threshold_, 1e-7);
        // A causal envelope cannot recognize the first sample of an isolated
        // attack. This bounded edge guard preserves that sample without adding
        // lookahead/delay. Polarity and channel order cannot change the gate.
        const bool firstEdge = peak > std::max(4 * before, 1e-5) && peak > 2 * lastPeak_;
        lastPeak_ = peak;
        if (hold_ > 0) {
            --hold_;
            if (hold_ == 0) { fade_ = fadeSamples_; inhibit_ = inhibitSamples_; }
            return 1;
        }
        if (inhibit_ > 0) --inhibit_;
        if (inhibit_ == 0 && (rising || firstEdge)) {
            hold_ = holdSamples_ - 1;
            fade_ = 0;
            return 1;
        }
        if (fade_ > 0) {
            const double phase = double(fade_--) / fadeSamples_;
            return .5 * (1 - std::cos(kPi * phase));
        }
        return 0;
    }
    void reset() {
        envelope_ = previous_ = second_ = current_ = mean_ = threshold_ = lastPeak_ = 0;
        samples_ = 0;
        hold_ = inhibit_ = fade_ = 0;
    }
private:
    double attack_, release_;
    int holdSamples_, inhibitSamples_, fadeSamples_;
    double envelope_ = 0, previous_ = 0, second_ = 0, current_ = 0;
    double mean_ = 0, threshold_ = 0, lastPeak_ = 0;
    std::uint64_t samples_ = 0;
    int hold_ = 0, inhibit_ = 0, fade_ = 0;
};
} // namespace

class StereoWidener::Impl {
public:
    Impl(int sampleRate, int algorithm) : rate_(checkedRate(sampleRate)),
        algorithm_(checkedAlgorithm(algorithm)), gate_(rate_),
        step_(1 - std::exp(-1 / (.010 * rate_))) {
        const double radiusMax = (1 - .015) / (1 + .015); // Source uses 15 ms.
        const double warp = .7464 * std::sqrt(2 / kPi * std::atan(.1418 * rate_)) + .03237;
        for (int ch = 0; ch < 2; ++ch) {
            auto& channel = channels_[ch];
            std::uint32_t seed = ch == 0 ? 0x9e3779b9U : 0x243f6a88U;
            for (auto& section : channel.allpass) {
                const double radius = .5 + (radiusMax - .5) * uniform(seed);
                const double angle = 2 * kPi * uniform(seed);
                // arg((warp + exp(i*a))/(1 + warp*exp(i*a))), evaluated without
                // constructing complex temporaries or using complex logarithms.
                const double warped = std::atan2((1 - warp * warp) * std::sin(angle),
                                                 2 * warp + (1 + warp * warp) * std::cos(angle));
                section.b0 = section.a2 = radius * radius;
                section.b1 = section.a1 = -2 * radius * std::cos(warped);
                section.b2 = 1;
            }
            double energy = 0;
            for (const auto& tap : kVelvetTaps[ch]) energy += tap.gain * tap.gain;
            const double gain = 1 / std::sqrt(energy);
            for (std::size_t i = 0; i < channel.taps.size(); ++i) {
                channel.taps[i] = {int(std::lround(kVelvetTaps[ch][i].delay * rate_ / 48000.0)),
                                   kVelvetTaps[ch][i].gain * gain};
            }
        }
        configure(1000);
    }

    void configure(double cutoff) {
        // Energy-preserving branch: source's eighth-order Butterworth, its
        // fixed 1000-Hz bilinear prewarp, and four SOS sections. Bounds keep
        // every pole inside the unit circle even at the 8-kHz input minimum.
        const double warp = 2 * kPi * 1000 / std::tan(kPi * 1000 / rate_);
        const double lowRatio = warp / (2 * kPi * cutoff);
        const double lrRatio = 1 / std::tan(kPi * cutoff / rate_);
        for (auto& channel : channels_) {
            for (int path = 0; path < 2; ++path) {
                for (int band = 0; band < 2; ++band) {
                    const bool low = band == 0;
                    const double ratio = low ? lowRatio : 1 / lowRatio;
                    for (int s = 0; s < kButterworthSections; ++s) {
                        const double cosTerm = std::cos(kPi * (2 * (s + 1) + 7) / 16);
                        const double denominator = ratio * ratio - 2 * ratio * cosTerm + 1;
                        auto& b = channel.butter[path][band][s];
                        b.b0 = b.b2 = 1 / denominator;
                        b.b1 = (low ? 2 : -2) / denominator;
                        b.a1 = (low ? -2 : 2) * (ratio * ratio - 1) / denominator;
                        b.a2 = (ratio * ratio + 2 * ratio * cosTerm + 1) / denominator;
                    }
                    // Source's stable LR2 branch: two first-order Butterworth
                    // factors in one SOS, with inversion of the high band.
                    const double denominator = lrRatio * lrRatio + 2 * lrRatio + 1;
                    auto& lr = channel.linkwitz[path][band];
                    lr.b0 = lr.b2 = (low ? 1 : lrRatio * lrRatio) / denominator;
                    lr.b1 = (low ? 2 : -2 * lrRatio * lrRatio) / denominator;
                    lr.a1 = 2 * (1 - lrRatio * lrRatio) / denominator;
                    lr.a2 = (lrRatio * lrRatio - 2 * lrRatio + 1) / denominator;
                }
            }
        }
    }

    static double approach(double current, double target, double step) {
        const double next = current + step * (target - current);
        return std::abs(next - target) < 1e-7 ? target : next;
    }

    void process(const float* input, float* output, std::size_t frames,
                 float low, float high, float cutoff, bool protect, bool amp) {
        if (frames == 0) return;
        if (!input || !output) throw std::invalid_argument("Null StereoWidener audio buffer");
        if (frames > std::numeric_limits<std::size_t>::max() / 2)
            throw std::invalid_argument("StereoWidener frame count overflows");
        const double targetLow = finiteBound(low, 0, 1, 0);
        const double targetHigh = finiteBound(high, 0, 1, 0);
        const double targetCutoff = finiteBound(cutoff, 200, std::min(4000.0, .45 * rate_), 1000);
        if (!initialized_) {
            low_ = targetLow; high_ = targetHigh; cutoff_ = targetCutoff;
            protect_ = protect ? 1 : 0; amp_ = amp ? 1 : 0;
            configure(cutoff_);
            appliedCutoff_ = cutoff_;
            initialized_ = true;
        }
        for (std::size_t frame = 0; frame < frames; ++frame) {
            const double x[2] = {finiteBound(input[frame * 2], -16, 16, 0),
                                 finiteBound(input[frame * 2 + 1], -16, 16, 0)};
            low_ = approach(low_, targetLow, step_);
            high_ = approach(high_, targetHigh, step_);
            cutoff_ = approach(cutoff_, targetCutoff, step_);
            amp_ = approach(amp_, amp ? 1 : 0, step_);
            protect_ = approach(protect_, protect ? 1 : 0, step_);
            if (coefficientPhase_++ == 0 && std::abs(cutoff_ - appliedCutoff_) > 1e-5) {
                configure(cutoff_);
                appliedCutoff_ = cutoff_;
            }
            coefficientPhase_ &= 15;
            const double amounts[2] = {low_, high_};
            const double wetGain[2] = {std::sin(.5 * kPi * low_), std::sin(.5 * kPi * high_)};
            const double dryGain[2] = {std::cos(.5 * kPi * low_), std::cos(.5 * kPi * high_)};
            const double protectGain = protect_ * gate_.process(x[0], x[1]);
            // At exactly zero the crossover's allpass phase must also bypass.
            // This short range makes the transition to/from dry continuous.
            const double active = std::min(1.0, 100 * std::max(amounts[0], amounts[1]));
            for (int ch = 0; ch < 2; ++ch) {
                auto& channel = channels_[ch];
                double decorrelated = 0;
                if (algorithm_ == 0) {
                    decorrelated = x[ch];
                    for (auto& section : channel.allpass) decorrelated = section.process(decorrelated);
                } else {
                    channel.delay[delayPosition_] = x[ch];
                    for (const auto& tap : channel.taps) {
                        int index = delayPosition_ - tap.delay;
                        if (index < 0) index += kVelvetBuffer;
                        decorrelated += tap.gain * channel.delay[index];
                    }
                }
                double widened = 0;
                for (int band = 0; band < 2; ++band) {
                    const double source[2] = {x[ch], decorrelated};
                    double filtered[2];
                    for (int path = 0; path < 2; ++path) {
                        double bw = source[path];
                        for (auto& section : channel.butter[path][band]) bw = section.process(bw);
                        const double lr = (band == 0 ? 1 : -1) * channel.linkwitz[path][band].process(source[path]);
                        filtered[path] = bw + amp_ * (lr - bw);
                    }
                    widened += dryGain[band] * filtered[0] + wetGain[band] * filtered[1];
                }
                const double effect = active * (1 - protectGain);
                output[frame * 2 + ch] = float(x[ch] + effect * (widened - x[ch]));
            }
            if (++delayPosition_ == kVelvetBuffer) delayPosition_ = 0;
        }
    }

    void reset() {
        for (auto& channel : channels_) {
            for (auto& section : channel.allpass) section.clear();
            channel.delay.fill(0);
            for (auto& path : channel.butter)
                for (auto& band : path)
                    for (auto& section : band) section.clear();
            for (auto& path : channel.linkwitz)
                for (auto& section : path) section.clear();
        }
        gate_.reset();
        delayPosition_ = coefficientPhase_ = 0;
        initialized_ = false;
    }
private:
    int rate_, algorithm_;
    std::array<Channel, 2> channels_{};
    TransientGate gate_;
    double step_, low_ = 0, high_ = 0, cutoff_ = 1000, appliedCutoff_ = 1000;
    double amp_ = 0, protect_ = 0;
    int delayPosition_ = 0, coefficientPhase_ = 0;
    bool initialized_ = false;
};

StereoWidener::StereoWidener(int sampleRate, int algorithm)
    : impl_(std::make_unique<Impl>(sampleRate, algorithm)) {}
StereoWidener::~StereoWidener() = default;
void StereoWidener::process(const float* input, float* output, std::size_t frames,
                           float low, float high, float crossover, bool protect, bool amp) {
    impl_->process(input, output, frames, low, high, crossover, protect, amp);
}
void StereoWidener::reset() { impl_->reset(); }
} // namespace spatial
