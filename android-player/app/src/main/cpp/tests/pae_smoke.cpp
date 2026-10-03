/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "pae_upmixer.h"

#include <algorithm>
#include <cmath>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kSqrtHalf = .7071067811865475244;
void require(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }
struct Rendered { std::vector<float> wave, components; };

Rendered render(spatial::PaeUpmixer& mixer, const std::vector<float>& input, bool fragmented = false) {
    const int frames = int(input.size() / 2), latency = mixer.latencyFrames();
    std::vector<float> padded = input;
    padded.resize(size_t(frames + latency) * 2, 0);
    Rendered result{std::vector<float>(size_t(frames + latency) * 8),
                    std::vector<float>(size_t(frames + latency) * 4)};
    const int chunks[] = {1, 17, 255, 997, 3, 1024, 89};
    int offset = 0, chunk = 0;
    while (offset < frames + latency) {
        const int count = std::min(frames + latency - offset, fragmented ? chunks[chunk++ % 7] : frames + latency);
        mixer.process(padded.data() + offset * 2, result.wave.data() + offset * 8, count,
                      result.components.data() + offset * 4);
        offset += count;
    }
    result.wave.erase(result.wave.begin(), result.wave.begin() + latency * 8);
    result.components.erase(result.components.begin(), result.components.begin() + latency * 4);
    for (float x : result.wave) require(std::isfinite(x), "non-finite 7.1 sample");
    for (float x : result.components) require(std::isfinite(x), "non-finite component sample");
    return result;
}

double energy(const std::vector<float>& data, int channels, int channel, int skip = 0) {
    double sum = 0;
    for (size_t f = skip; f + size_t(skip) < data.size() / channels; ++f)
        sum += double(data[f * channels + channel]) * data[f * channels + channel];
    return sum;
}

double correlation(const std::vector<float>& data, int ch1, int ch2, int skip) {
    double cross = 0;
    for (size_t f = skip; f + size_t(skip) < data.size() / 8; ++f)
        cross += double(data[f * 8 + ch1]) * data[f * 8 + ch2];
    return cross / std::sqrt(energy(data, 8, ch1, skip) * energy(data, 8, ch2, skip));
}

void close(const std::vector<float>& a, const std::vector<float>& b, double tolerance = 1e-6) {
    require(a.size() == b.size(), "different sample counts");
    for (size_t n = 0; n < a.size(); ++n)
        if (std::abs(double(a[n]) - b[n]) > tolerance)
            throw std::runtime_error("sample mismatch at " + std::to_string(n));
}

void reconstructed(const std::vector<float>& input, const Rendered& output) {
    require(input.size() * 2 == output.components.size(), "component frame count changed");
    for (size_t f = 0; f < input.size() / 2; ++f) {
        for (int channel = 0; channel < 2; ++channel) {
            const double p = output.components[f * 4 + channel];
            const double a = output.components[f * 4 + channel + 2];
            if (std::abs(p + a - input[f * 2 + channel]) > 2e-6)
                throw std::runtime_error("P+A reconstruction/latency mismatch at frame " + std::to_string(f));
            const double front = output.wave[f * 8 + channel];
            const double centerFold = kSqrtHalf * output.wave[f * 8 + 2];
            require(std::abs(front + centerFold - p - .5 * a) < 2e-6,
                    "center fold does not reconstruct front primary plus retained ambience");
        }
    }
}

std::vector<float> noise(int frames, unsigned seed) {
    std::mt19937 random(seed);
    std::uniform_real_distribution<float> value(-.15f, .15f);
    std::vector<float> data(frames * 2);
    for (auto& x : data) x = value(random);
    return data;
}

void checkSpecial(const std::vector<float>& data, int mode) {
    spatial::PaeUpmixer mixer(48000);
    const auto result = render(mixer, data, true);
    reconstructed(data, result);
    const double inputEnergy = energy(data, 2, 0) + energy(data, 2, 1);
    const double ambientEnergy = energy(result.components, 4, 2) + energy(result.components, 4, 3);
    require(ambientEnergy < inputEnergy * 1e-10, "coherent primary leaked into ambient");
    if (mode == 0) { // exact dual mono -> common center
        require(energy(result.wave, 8, 0) + energy(result.wave, 8, 1) < inputEnergy * 1e-10,
                "mono primary remained in side fronts");
        require(energy(result.wave, 8, 2) > inputEnergy * .999, "mono center energy changed");
    } else {
        require(energy(result.wave, 8, 2) < inputEnergy * 1e-10, "hard-pan or anti-phase pulled into center");
        if (mode == 1) require(energy(result.wave, 8, 3) < inputEnergy * 1e-10, "anti-phase created mono LFE");
    }
}
} // namespace

int main() {
    try {
        spatial::PaeUpmixer mixer(48000);
        require(mixer.latencyFrames() == 1024, "PAE buffering delay changed");
        auto input = noise(48013, 1);
        const auto whole = render(mixer, input);
        reconstructed(input, whole);
        mixer.reset();
        const auto chunks = render(mixer, input, true);
        close(whole.wave, chunks.wave, 0);
        close(whole.components, chunks.components, 0);

        for (int frames : {1, 17, 255, 256, 257, 1023, 1024, 1025}) {
            mixer.reset();
            auto shortInput = noise(frames, unsigned(frames));
            shortInput.front() = .2f;
            shortInput.back() = -.3f;
            reconstructed(shortInput, render(mixer, shortInput, true));
        }

        auto mono = noise(13013, 3), anti = mono, hardLeft = mono, hardRight = mono;
        for (size_t f = 0; f < mono.size() / 2; ++f) {
            mono[2 * f + 1] = mono[2 * f];
            anti[2 * f + 1] = -anti[2 * f];
            hardLeft[2 * f + 1] = 0;
            hardRight[2 * f] = 0;
        }
        checkSpecial(mono, 0);
        checkSpecial(anti, 1);
        checkSpecial(hardLeft, 2);
        checkSpecial(hardRight, 2);

        // Fully complex coherence: a quadrature primary must stay primary,
        // but must not become a spurious common center or vanish in a mid sum.
        std::vector<float> quadrature(24000 * 2);
        for (int f = 0; f < 24000; ++f) {
            quadrature[2 * f] = float(.15 * std::sin(2 * kPi * 750 * f / 48000));
            quadrature[2 * f + 1] = float(.15 * std::cos(2 * kPi * 750 * f / 48000));
        }
        mixer.reset();
        const auto quadratureResult = render(mixer, quadrature);
        reconstructed(quadrature, quadratureResult);
        const double quadratureEnergy = energy(quadrature, 2, 0, 2048) + energy(quadrature, 2, 1, 2048);
        require(energy(quadratureResult.components, 4, 2, 2048) + energy(quadratureResult.components, 4, 3, 2048)
                    < quadratureEnergy * .005, "complex coherent source classified as ambience");
        require(energy(quadratureResult.wave, 8, 2, 2048) < quadratureEnergy * .005,
                "quadrature source pulled into common center");

        // Known independent ambient components plus an in-phase primary:
        // measure extraction error against ground truth, not just nonzero rear.
        auto primary = noise(48000, 19), ambience = noise(48000, 37);
        std::vector<float> mixture(primary.size());
        for (size_t f = 0; f < mixture.size() / 2; ++f)
            for (int ch = 0; ch < 2; ++ch)
                mixture[2 * f + ch] = primary[2 * f] + .55f * ambience[2 * f + ch];
        mixer.reset();
        const auto separated = render(mixer, mixture);
        reconstructed(mixture, separated);
        double primaryError = 0, inputError = 0;
        for (int f = 4096; f < 48000 - 4096; ++f) {
            for (int ch = 0; ch < 2; ++ch) {
                const double error = separated.components[4 * f + ch] - primary[2 * f];
                primaryError += error * error;
                const double originalError = mixture[2 * f + ch] - primary[2 * f];
                inputError += originalError * originalError;
            }
        }
        require(primaryError < inputError * .85, "PAE failed to improve known primary estimate");

        for (int ch : {4, 5, 6, 7})
            require(energy(whole.wave, 8, ch, 4096) > .01, "diffuse output channel is silent");
        for (int ch1 = 4; ch1 < 8; ++ch1)
            for (int ch2 = ch1 + 1; ch2 < 8; ++ch2)
                require(std::abs(correlation(whole.wave, ch1, ch2, 4096)) < .8,
                        "ambient channels insufficiently decorrelated on broadband test");

        double lfeEnergy[2]{};
        for (int tone = 0; tone < 2; ++tone) {
            const double hz = tone == 0 ? 40 : 1000;
            std::vector<float> signal(12000 * 2);
            for (int f = 0; f < 12000; ++f)
                signal[2 * f] = signal[2 * f + 1] = float(.15 * std::sin(2 * kPi * hz * f / 48000));
            mixer.reset();
            const auto result = render(mixer, signal);
            lfeEnergy[tone] = energy(result.wave, 8, 3, 2048);
        }
        require(lfeEnergy[0] > .1 && lfeEnergy[1] < lfeEnergy[0] * 1e-5,
                "LFE 120 Hz low-pass missing or incorrectly routed");

        for (int rate : {8000, 44100, 96000}) {
            spatial::PaeUpmixer rateMixer(rate);
            reconstructed(input, render(rateMixer, input, true));
        }
        mixer.reset();
        auto invalidSamples = noise(2000, 90);
        invalidSamples[0] = std::numeric_limits<float>::quiet_NaN();
        invalidSamples[1] = std::numeric_limits<float>::infinity();
        invalidSamples[2] = std::numeric_limits<float>::max();
        render(mixer, invalidSamples, true);
        mixer.process(nullptr, nullptr, 0);
        bool rejected = false;
        try { mixer.process(nullptr, nullptr, 1); } catch (const std::invalid_argument&) { rejected = true; }
        require(rejected, "null nonempty input accepted");
        rejected = false;
        try { spatial::PaeUpmixer invalid(0); } catch (const std::invalid_argument&) { rejected = true; }
        require(rejected, "invalid sample rate accepted");
        std::cout << "PASS: Wiener PAE reconstruction, latency/EOS, arbitrary chunks/reset, mono, anti-phase, "
                     "hard pans, complex coherence, known-source MSE, four diffuse channels, LFE, rates, finite input. "
                  << "Primary MSE/input MSE=" << primaryError / inputError << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
