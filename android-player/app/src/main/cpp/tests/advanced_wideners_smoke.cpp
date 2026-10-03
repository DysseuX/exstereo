/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "advanced_wideners.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <vector>

namespace { bool trackAllocations = false; std::size_t allocations = 0; }
void* operator new(std::size_t count) {
    if (trackAllocations) ++allocations;
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
constexpr std::array<int, 4> algorithms{{6, 8, 9, 10}};
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
void near(const std::vector<float>& a, const std::vector<float>& b, double tolerance = 0) {
    require(a.size() == b.size(), "Changed number of samples");
    for (std::size_t i = 0; i < a.size(); ++i)
        require(std::isfinite(a[i]) && std::abs(double(a[i]) - b[i]) <= tolerance,
                "Sample/reference/chunk/reset mismatch");
}
std::vector<float> noise(int frames, bool mono = false) {
    std::vector<float> result(std::size_t(frames) * 2);
    std::uint32_t seed = 95471;
    for (int i = 0; i < frames; ++i) {
        seed = seed * 1664525U + 1013904223U;
        result[std::size_t(i) * 2] = .15f * (float(seed >> 8) / 8388608.f - 1);
        seed = seed * 1664525U + 1013904223U;
        result[std::size_t(i) * 2 + 1] = mono ? result[std::size_t(i) * 2]
            : .15f * (float(seed >> 8) / 8388608.f - 1);
    }
    return result;
}
void process(spatial::AdvancedWideners& processor, const std::vector<float>& input,
             std::vector<float>& output, int algorithm,
             const spatial::AdvancedWidthSettings& settings, bool fragmented = false) {
    const std::array<int, 8> chunks{{1, 17, 128, 1024, 3, 509, 11, 2063}};
    int position = 0, part = 0;
    const int frames = int(input.size() / 2);
    while (position < frames) {
        const int count = std::min(frames - position,
            fragmented ? chunks[std::size_t(part++) % chunks.size()] : frames);
        trackAllocations = true;
        processor.process(input.data() + std::size_t(position) * 2,
                          output.data() + std::size_t(position) * 2, count, algorithm, settings);
        trackAllocations = false;
        position += count;
    }
}
void sameMid(const std::vector<float>& input, const std::vector<float>& output) {
    for (std::size_t i = 0; i < input.size(); i += 2)
        require(std::abs((double(input[i]) + input[i + 1]) -
                         (double(output[i]) + output[i + 1])) < 8e-8,
                "Mono sum changed before the external limiter");
}
double sideRms(const std::vector<float>& output, int start) {
    double sum = 0;
    const int count = int(output.size() / 2);
    for (int i = start; i < count; ++i) {
        const double side = .5 * (double(output[std::size_t(i) * 2]) - output[std::size_t(i) * 2 + 1]);
        sum += side * side;
    }
    return std::sqrt(sum / (count - start));
}

void streaming(int rate) {
    const auto input = noise(rate / 2);
    spatial::AdvancedWidthSettings settings;
    spatial::AdvancedWideners shared(rate);
    for (int algorithm : algorithms) {
        spatial::AdvancedWideners whole(rate), fragmented(rate);
        std::vector<float> expected(input.size()), result(input.size());
        process(whole, input, expected, algorithm, settings);
        process(fragmented, input, result, algorithm, settings, true);
        near(expected, result);
        fragmented.reset();
        process(fragmented, input, result, algorithm, settings);
        near(expected, result);
        auto inPlace = input;
        fragmented.reset();
        process(fragmented, inPlace, inPlace, algorithm, settings, true);
        near(expected, inPlace);
        process(shared, input, result, algorithm, settings, true);
        near(expected, result); // Histories of the four algorithms are independent.
        if (algorithm != 10) sameMid(input, result);
    }
    require(allocations == 0, "Audio processing allocated memory");
}

std::vector<float> sideTone(int rate, double frequency, double midAmplitude = 0) {
    std::vector<float> result(std::size_t(rate) * 2);
    for (int i = 0; i < rate; ++i) {
        const double side = .1 * std::sin(2 * pi * frequency * i / rate);
        const double mid = midAmplitude * std::sin(2 * pi * 317 * i / rate);
        result[std::size_t(i) * 2] = float(mid + side);
        result[std::size_t(i) * 2 + 1] = float(mid - side);
    }
    return result;
}
// Independent z-domain response of the complementary one-pole split. This
// compares measured steady-state tone levels, not private implementation state.
std::complex<double> lowpassResponse(int rate, double cutoff, double frequency) {
    const double pole = std::exp(-2 * pi * cutoff / rate);
    return (1 - pole) / (1.0 - pole * std::exp(std::complex<double>(0, -2 * pi * frequency / rate)));
}
void bandsAndShelf(int rate) {
    spatial::AdvancedWidthSettings settings;
    settings.bandWidths = {{.5f, 1.2f, 1.8f}};
    settings.correlationGuard = false;
    for (double frequency : {70.0, 1000.0, std::min(8000.0, .3 * rate)}) {
        const auto input = sideTone(rate, frequency, .035);
        std::vector<float> output(input.size());
        spatial::AdvancedWideners bands(rate);
        process(bands, input, output, 6, settings, true);
        const auto low = lowpassResponse(rate, settings.lowCrossover, frequency);
        const auto high = lowpassResponse(rate, settings.highCrossover, frequency);
        const auto response = double(settings.bandWidths[0]) * low +
            double(settings.bandWidths[1]) * (high - low) +
            double(settings.bandWidths[2]) * (1.0 - high);
        const double measured = sideRms(output, rate / 2) / sideRms(input, rate / 2);
        require(std::abs(measured - std::abs(response)) < 2e-5, "Multiband tone response mismatch");
        sameMid(input, output);
        for (float amount : {-.75f, .75f}) {
            settings.shuffleAmount = amount;
            spatial::AdvancedWideners shelf(rate);
            process(shelf, input, output, 9, settings, true);
            const auto expected = 1.0 + double(amount) * lowpassResponse(rate, settings.shuffleFrequency, frequency);
            const double gain = sideRms(output, rate / 2) / sideRms(input, rate / 2);
            require(std::abs(gain - std::abs(expected)) < 2e-5, "Shuffler shelf response mismatch");
            sameMid(input, output);
        }
    }
    const auto input = noise(rate / 2);
    std::vector<float> output(input.size());
    settings.bandWidths = {{1, 1, 1}};
    settings.correlationGuard = true;
    spatial::AdvancedWideners neutral(rate);
    process(neutral, input, output, 6, settings, true);
    near(input, output);
    settings.sideAmount = 0;
    process(neutral, input, output, 8, settings, true);
    near(input, output);
    settings.shuffleAmount = 0;
    process(neutral, input, output, 9, settings, true);
    near(input, output);
}

void correlationGuard() {
    constexpr int rate = 48000;
    spatial::AdvancedWidthSettings settings;
    settings.bandWidths = {{2, 2, 2}};
    for (double midAmplitude : {0.0, .12}) {
        auto input = sideTone(rate, 887, midAmplitude);
        // Long enough for the meter and guard to settle; concatenate identical
        // integer-cycle seconds so the splice does not introduce an impulse.
        auto second = input;
        input.insert(input.end(), second.begin(), second.end());
        std::vector<float> guarded(input.size()), unguarded(input.size());
        spatial::AdvancedWideners on(rate), off(rate);
        settings.correlationGuard = true;
        process(on, input, guarded, 6, settings, true);
        settings.correlationGuard = false;
        process(off, input, unguarded, 6, settings, true);
        const double original = sideRms(input, rate), actual = sideRms(guarded, rate);
        require(std::abs(sideRms(unguarded, rate) / original - 2) < 1e-5,
                "Guard-off uniform width is not exact");
        // Pre-existing antiphase stays present (not muted); a correlated input
        // allows added side only up to approximately its Mid RMS.
        const double expected = midAmplitude == 0 ? original : midAmplitude / std::sqrt(2.0);
        require(actual > .96 * expected && actual < 1.04 * expected,
                "Correlation guard failed to contain additional side energy");
        sameMid(input, guarded);
    }
}

void synthesizedSide(int rate) {
    const auto input = noise(rate, true);
    std::vector<float> output(input.size());
    spatial::AdvancedWidthSettings settings;
    settings.sideAmount = .8f;
    spatial::AdvancedWideners effect(rate);
    process(effect, input, output, 8, settings, true);
    sameMid(input, output);
    require(sideRms(output, rate / 2) > .02, "Mono did not produce a side signal");
    auto bass = sideTone(rate, 30);
    for (std::size_t i = 0; i < bass.size(); i += 2) bass[i + 1] = bass[i];
    spatial::AdvancedWideners highpassLow(rate), highpassHigh(rate);
    settings.sideHighpass = 20;
    process(highpassLow, bass, output, 8, settings);
    const double lowCutEnergy = sideRms(output, rate / 2);
    settings.sideHighpass = 2000;
    process(highpassHigh, bass, output, 8, settings);
    require(sideRms(output, rate / 2) < lowCutEnergy * .04,
            "Side synthesis did not protect low frequencies");
}

void pitchDelayOracle(int rate) {
    const auto input = noise(rate / 3);
    std::vector<float> output(input.size()), expected(input.size());
    spatial::AdvancedWidthSettings settings;
    settings.microCents = 0;
    settings.microDelayMs = 7.3f;
    spatial::AdvancedWideners pitch(rate);
    process(pitch, input, output, 10, settings, true);
    const double delay = double(settings.microDelayMs) * rate / 1000;
    for (int i = 0; i < int(input.size() / 2); ++i) {
        const double source = i - delay;
        const int before = int(std::floor(source));
        const double fraction = source - before;
        for (int channel = 0; channel < 2; ++channel) {
            const double a = before >= 0 ? input[std::size_t(before) * 2 + channel] : 0;
            const double b = before + 1 >= 0 ? input[std::size_t(before + 1) * 2 + channel] : 0;
            expected[std::size_t(i) * 2 + channel] = float((1 - fraction) * a + fraction * b);
        }
    }
    near(output, expected, 2e-7);
}
double crossingFrequency(const std::vector<float>& output, int rate, int channel) {
    double first = 0, last = 0;
    int crossings = 0;
    for (int frame = rate / 2 + 1; frame < int(output.size() / 2); ++frame) {
        const double before = output[std::size_t(frame - 1) * 2 + channel];
        const double after = output[std::size_t(frame) * 2 + channel];
        if (before <= 0 && after > 0) {
            const double position = frame - 1 + (-before) / (after - before);
            if (crossings == 0) first = position;
            last = position;
            ++crossings;
        }
    }
    require(crossings > 100, "MicroPitch lost its test tone");
    return (crossings - 1) * rate / (last - first);
}
void actualDetuning() {
    constexpr int rate = 48000;
    std::vector<float> input(std::size_t(rate) * 8), output(input.size());
    for (int frame = 0; frame < rate * 4; ++frame) {
        const float sample = float(.15 * std::sin(2 * pi * 1000 * frame / rate));
        input[std::size_t(frame) * 2] = input[std::size_t(frame) * 2 + 1] = sample;
    }
    spatial::AdvancedWidthSettings settings;
    settings.microCents = 20;
    settings.microDelayMs = 20;
    spatial::AdvancedWideners pitch(rate);
    process(pitch, input, output, 10, settings, true);
    const double leftFrequency = crossingFrequency(output, rate, 0);
    const double rightFrequency = crossingFrequency(output, rate, 1);
    require(std::abs(leftFrequency - 1000 * std::exp2(20.0 / 1200)) < .4 &&
            std::abs(rightFrequency - 1000 * std::exp2(-20.0 / 1200)) < .4,
            "MicroPitch is not opposite constant detuning");
    std::cout << "MicroPitch measured Hz: " << leftFrequency << ", " << rightFrequency << '\n';
}

void automation() {
    constexpr int rate = 48000, eventFrames = 997, events = 24;
    const auto input = noise(eventFrames * events);
    std::vector<float> contiguous(input.size()), fragmented(input.size());
    for (int algorithm : algorithms) {
        spatial::AdvancedWideners one(rate), many(rate);
        spatial::AdvancedWidthSettings settings;
        for (int event = 0; event < events; ++event) {
            const float sweep = float(event % 7) / 6;
            settings.bandWidths = {{sweep * 2, 2 - sweep * 2, .5f + sweep}};
            settings.lowCrossover = 40 + sweep * 1160;
            settings.highCrossover = 800 + sweep * 11200;
            settings.correlationGuard = (event & 1) != 0;
            settings.sideAmount = sweep;
            settings.sideHighpass = 20 + sweep * 1980;
            settings.shuffleAmount = sweep * 2 - 1;
            settings.shuffleFrequency = 80 + sweep * 2920;
            settings.microCents = sweep * 20;
            settings.microDelayMs = 1 + sweep * 39;
            const std::size_t start = std::size_t(event) * eventFrames * 2;
            trackAllocations = true;
            one.process(input.data() + start, contiguous.data() + start, eventFrames, algorithm, settings);
            for (int offset = 0; offset < eventFrames; ) {
                const int frames = std::min(37, eventFrames - offset);
                many.process(input.data() + start + std::size_t(offset) * 2,
                    fragmented.data() + start + std::size_t(offset) * 2, frames, algorithm, settings);
                offset += frames;
            }
            trackAllocations = false;
        }
        near(contiguous, fragmented);
    }
    spatial::AdvancedWideners ramp(rate);
    spatial::AdvancedWidthSettings settings;
    settings.bandWidths = {{1, 1, 1}};
    settings.correlationGuard = false;
    std::vector<float> constant(2000), output(2000);
    for (std::size_t i = 0; i < constant.size(); i += 2) { constant[i] = .1f; constant[i + 1] = -.1f; }
    process(ramp, constant, output, 6, settings);
    settings.bandWidths = {{2, 2, 2}};
    ramp.process(constant.data(), output.data(), 1, 6, settings);
    const double expected = double(constant[0]) * (2 - std::exp(-1 / (.020 * rate)));
    require(std::abs(output[0] - expected) < 2e-8, "Width parameter did not ramp sample-wise");
}

void hostileValues() {
    for (int rate : {8000, 96000}) {
        auto input = noise(rate / 4);
        input[0] = std::numeric_limits<float>::quiet_NaN();
        input[1] = std::numeric_limits<float>::infinity();
        input[2] = -std::numeric_limits<float>::infinity();
        input[3] = std::numeric_limits<float>::max();
        input[4] = -std::numeric_limits<float>::max();
        std::vector<float> output(input.size());
        spatial::AdvancedWideners effect(rate);
        spatial::AdvancedWidthSettings settings;
        for (int algorithm : algorithms) {
            for (int step = 0; step < 8; ++step) {
                const float extreme = step % 3 == 0 ? std::numeric_limits<float>::quiet_NaN()
                    : (step % 3 == 1 ? 1e30f : -1e30f);
                settings.bandWidths = {{extreme, extreme, extreme}};
                settings.lowCrossover = settings.highCrossover = extreme;
                settings.sideAmount = settings.sideHighpass = extreme;
                settings.shuffleAmount = settings.shuffleFrequency = extreme;
                settings.microCents = settings.microDelayMs = extreme;
                settings.correlationGuard = (step & 1) != 0;
                process(effect, input, output, algorithm, settings, true);
                for (float sample : output)
                    require(std::isfinite(sample) && std::abs(sample) < 1024,
                            "Nonfinite or unbounded hostile-input output");
            }
        }
        effect.process(nullptr, nullptr, 0, 6, settings);
        bool failed = false;
        try { effect.process(input.data(), output.data(), -1, 6, settings); }
        catch (const std::invalid_argument&) { failed = true; }
        require(failed, "Negative frame count accepted");
        failed = false;
        try { effect.process(input.data(), output.data(), 1, 7, settings); }
        catch (const std::invalid_argument&) { failed = true; }
        require(failed, "Unknown algorithm accepted");
        failed = false;
        try { effect.process(nullptr, output.data(), 1, 6, settings); }
        catch (const std::invalid_argument&) { failed = true; }
        require(failed, "Null input accepted");
    }
    for (int rate : {0, 7999, 96001, std::numeric_limits<int>::max()}) {
        bool failed = false;
        try { spatial::AdvancedWideners invalid(rate); }
        catch (const std::invalid_argument&) { failed = true; }
        require(failed, "Unsupported rate accepted");
    }
}
} // namespace

int main() {
    try {
        for (int rate : {8000, 44100, 48000, 96000}) {
            streaming(rate);
            bandsAndShelf(rate);
            synthesizedSide(rate);
            pitchDelayOracle(rate);
        }
        correlationGuard();
        actualDetuning();
        automation();
        hostileValues();
        require(allocations == 0, "Audio processing allocated memory");
        std::cout << "Advanced widening: independent tone/delay oracles, Mid/sum, "
            "correlation guard, mono synthesis, pitch frequencies, chunks/reset/in-place, "
            "bounds and zero process allocations PASS\n";
        return 0;
    } catch (const std::exception& error) {
        trackAllocations = false;
        std::cerr << "Advanced widening FAILED: " << error.what() << '\n';
        return 1;
    }
}
