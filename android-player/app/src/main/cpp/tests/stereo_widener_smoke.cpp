/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "stereo_widener.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <vector>

// Count C++ allocations only while the actual audio call runs. Test fixtures,
// construction and assertion messages are deliberately outside that interval.
namespace { bool countAllocations = false; std::size_t allocations = 0; }
void* operator new(std::size_t count) {
    if (countAllocations) ++allocations;
    if (void* p = std::malloc(count ? count : 1)) return p;
    throw std::bad_alloc();
}
void* operator new[](std::size_t count) { return ::operator new(count); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }

namespace {
constexpr double pi = 3.14159265358979323846;
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
std::vector<float> monoNoise(int frames) {
    std::vector<float> result(std::size_t(frames) * 2);
    std::uint32_t state = 1234567;
    for (int i = 0; i < frames; ++i) {
        state = state * 1664525U + 1013904223U;
        const float x = .2f * (float(state >> 8) / 8388608.0f - 1);
        result[i * 2] = result[i * 2 + 1] = x;
    }
    return result;
}
void near(const std::vector<float>& a, const std::vector<float>& b, double epsilon) {
    require(a.size() == b.size(), "frame count changed");
    for (std::size_t i = 0; i < a.size(); ++i)
        require(std::isfinite(a[i]) && std::abs(double(a[i]) - b[i]) <= epsilon,
                "reference/chunk/reset mismatch");
}
double correlation(const std::vector<float>& samples) {
    double l = 0, r = 0, lr = 0;
    for (std::size_t i = 0; i < samples.size(); i += 2) {
        l += double(samples[i]) * samples[i];
        r += double(samples[i + 1]) * samples[i + 1];
        lr += double(samples[i]) * samples[i + 1];
    }
    return lr / std::sqrt(l * r);
}
void chunks(spatial::StereoWidener& widener, const std::vector<float>& input,
            std::vector<float>& output, bool fragmented, float low = .2f,
            float high = .8f, float cutoff = 1000, bool protect = true,
            bool amp = false) {
    constexpr int sizes[] = {1, 17, 255, 1024, 7, 511, 89};
    std::size_t at = 0, part = 0;
    while (at < input.size() / 2) {
        const auto frames = std::min(input.size() / 2 - at,
                                    fragmented ? std::size_t(sizes[part++ % 7]) : input.size() / 2);
        countAllocations = true;
        widener.process(input.data() + at * 2, output.data() + at * 2, frames,
                        low, high, cutoff, protect, amp);
        countAllocations = false;
        at += frames;
    }
}

// Independent oracle: for equal widths=100% the two inverted LR2 bands sum
// to a FIRST-order allpass (a+z^-1)/(1+a*z^-1). Convolve the actual pinned
// optimized velvet FIR, normalize its energy, then evaluate that recurrence.
// This does not reuse the port's SOS code or coefficients.
void velvetOracle(int rate) {
    constexpr std::array<std::array<int, 15>, 2> positions{{
        {{0,48,96,144,191,240,288,335,384,432,480,528,576,624,672}},
        {{0,48,96,144,192,239,288,336,383,432,480,528,576,624,672}}
    }};
    constexpr std::array<std::array<double, 15>, 2> gains{{
        {{1,1.058,-.5971,.4181,-.2201,-.0441,-.0358,.0774,-.032,-.0071,-.0039,.0097,.0019,.0012,-.0032}},
        {{-1,.9928,.553,.4268,-.0525,.1981,.1238,-.0155,.0339,-.0053,-.0039,-.0042,.0014,-.0036,-.0026}}
    }};
    constexpr int frames = 4096;
    std::vector<float> input(frames * 2), output(frames * 2), expected(frames * 2);
    input[0] = input[1] = 1;
    const double ratio = 1 / std::tan(pi * 1000 / rate);
    const double a = (1 - ratio) / (1 + ratio);
    for (int channel = 0; channel < 2; ++channel) {
        std::vector<double> fir(frames);
        double energy = 0;
        for (double gain : gains[channel]) energy += gain * gain;
        for (std::size_t tap = 0; tap < gains[channel].size(); ++tap)
            fir[std::lround(positions[channel][tap] * rate / 48000.0)] += gains[channel][tap] / std::sqrt(energy);
        double previousInput = 0, previousOutput = 0;
        for (int i = 0; i < frames; ++i) {
            const double y = a * fir[i] + previousInput - a * previousOutput;
            expected[i * 2 + channel] = float(y);
            previousInput = fir[i]; previousOutput = y;
        }
    }
    spatial::StereoWidener widener(rate, 1);
    chunks(widener, input, output, true, 1, 1, 1000, false, true);
    near(output, expected, 2e-6);
}

void checkRate(int rate, int algorithm) {
    auto input = monoNoise(rate / 3);
    std::vector<float> whole(input.size()), fragmented(input.size()), reset(input.size());
    spatial::StereoWidener a(rate, algorithm), b(rate, algorithm);
    chunks(a, input, whole, false);
    chunks(b, input, fragmented, true);
    near(whole, fragmented, 0);
    a.reset();
    chunks(a, input, reset, true);
    near(whole, reset, 0);

    // Zero width must bypass the crossover phase as well as decorrelation.
    a.reset();
    chunks(a, input, whole, true, 0, 0, 4000, false);
    near(whole, input, 0);
    a.reset();
    chunks(a, input, whole, true, 0, 0, 200, true, true);
    near(whole, input, 0);

    a.reset();
    chunks(a, input, whole, true, .75f, .75f, 1000, false, true);
    require(std::abs(correlation(whole)) < .9, "dual mono did not decorrelate");

    // Exact in-place processing must match disjoint output buffers.
    b.reset();
    auto inPlace = input;
    b.process(inPlace.data(), inPlace.data(), input.size() / 2, .75f, .75f, 1000, false, true);
    near(whole, inPlace, 0);

    // Every channel is protected by the same gate, including negative attacks.
    std::vector<float> impulse(std::size_t(rate / 5) * 2, 0), protectedOutput(impulse.size());
    impulse[0] = -.8f; impulse[1] = .4f;
    a.reset();
    chunks(a, impulse, protectedOutput, true, 1, 1, 1000, true);
    for (int frame = 0; frame < int(.070 * rate); ++frame) {
        require(protectedOutput[frame * 2] == impulse[frame * 2] &&
                protectedOutput[frame * 2 + 1] == impulse[frame * 2 + 1],
                "attack/hold must remain original, linked and polarity independent");
    }

    // Arbitrary control changes, min-rate Nyquist clamp, invalid floats and
    // alternating filterbanks remain bounded and recover from invalid input.
    input[9] = std::numeric_limits<float>::quiet_NaN();
    input[17] = std::numeric_limits<float>::infinity();
    a.reset();
    for (std::size_t at = 0; at < input.size() / 2; at += 127) {
        const auto count = std::min<std::size_t>(127, input.size() / 2 - at);
        const bool even = (at / 127) % 2 == 0;
        a.process(input.data() + at * 2, whole.data() + at * 2, count,
                  even ? -10.f : 10.f, even ? 10.f : std::numeric_limits<float>::quiet_NaN(),
                  even ? 200.f : 999999.f, even, !even);
    }
    for (float sample : whole)
        require(std::isfinite(sample) && std::abs(sample) < 8, "unstable automated/invalid parameters");

    // A disabled effect cannot leave a filterbank phase shift after its ramp.
    std::vector<float> silence(std::size_t(rate / 3) * 2), tail(silence.size());
    a.process(silence.data(), tail.data(), silence.size() / 2, 0, 0, 1000, false);
    for (std::size_t i = tail.size() - 200; i < tail.size(); ++i)
        require(tail[i] == 0, "zero-width bypass did not settle to exact silence");
    a.process(nullptr, nullptr, 0, 0, 0, 1000, true);
}

void allpassEnergy() {
    constexpr int frames = 24000;
    std::vector<float> input(frames * 2), output(frames * 2);
    input[0] = input[1] = 1;
    spatial::StereoWidener widener(48000, 0);
    chunks(widener, input, output, true, 1, 1, 1000, false, true);
    for (int channel = 0; channel < 2; ++channel) {
        double energy = 0;
        for (int frame = 0; frame < frames; ++frame)
            energy += double(output[frame * 2 + channel]) * output[frame * 2 + channel];
        require(std::abs(energy - 1) < 1e-5, "allpass cascade changed impulse energy");
    }
}
} // namespace

int main() {
    try {
        for (int rate : {8000, 48000, 96000}) {
            velvetOracle(rate);
            for (int algorithm : {0, 1}) checkRate(rate, algorithm);
        }
        allpassEnergy();
        for (int rate : {0, 7999, 96001}) {
            bool rejected = false;
            try { spatial::StereoWidener invalid(rate); }
            catch (const std::invalid_argument&) { rejected = true; }
            require(rejected, "invalid rate accepted");
        }
        bool rejected = false;
        try { spatial::StereoWidener invalid(48000, 2); }
        catch (const std::invalid_argument&) { rejected = true; }
        require(rejected, "invalid algorithm accepted");
        require(allocations == 0, "audio processing allocated heap memory");
        std::cout << "StereoWidener: reference FIR/LR2, allpass energy, chunk/reset, "
                     "transients, zero bypass, automation and allocation checks passed\n";
    } catch (const std::exception& error) {
        countAllocations = false;
        std::cerr << error.what() << '\n';
        return 1;
    }
}
