/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "openal_renderer.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace {
const std::array<float, 7> kAz{{-30, 30, 0, -150, 150, -90, 90}};
const std::array<float, 7> kEl{};
constexpr int kRate = 48000;
constexpr int kFrames = 4096;

void require(bool condition, const char* description) {
    if (!condition) throw std::runtime_error(description);
}

std::vector<float> render(spatial::OpenAlRenderer& renderer, const std::vector<float>& input,
                          const std::vector<int>& chunks,
                          const std::array<float, 7>& az = kAz,
                          const std::array<float, 7>& el = kEl) {
    const int frames = static_cast<int>(input.size() / 8);
    std::vector<float> output(static_cast<size_t>(frames) * 2);
    int offset = 0;
    size_t step = 0;
    while (offset < frames) {
        const int n = std::min(frames - offset, chunks[step++ % chunks.size()]);
        renderer.render(input.data() + offset * 8, output.data() + offset * 2, n, az, el);
        offset += n;
    }
    for (float value : output) require(std::isfinite(value), "non-finite OpenAL output");
    return output;
}

double energy(const std::vector<float>& signal, int ear) {
    double result = 0.0;
    for (size_t i = static_cast<size_t>(ear); i < signal.size(); i += 2)
        result += static_cast<double>(signal[i]) * signal[i];
    return result;
}

double difference(const std::vector<float>& first, const std::vector<float>& second) {
    double result = 0.0;
    for (size_t i = 0; i < first.size(); ++i)
        result = std::max(result, std::abs(static_cast<double>(first[i]) - second[i]));
    return result;
}

void directionTests() {
    // An impulse reveals both the actual HRTF and left/right channel mapping;
    // plain stereo panning cannot pass the different-height response check.
    const std::array<int, 7> channels{{0, 1, 2, 4, 5, 6, 7}};
    for (int speaker = 0; speaker < 7; ++speaker) {
        spatial::OpenAlRenderer renderer(kRate);
        std::vector<float> input(kFrames * 8);
        input[channels[speaker]] = 0.5f;
        const auto output = render(renderer, input, {256});
        const auto left = energy(output, 0);
        const auto right = energy(output, 1);
        require(left > 1e-8 && right > 1e-8, "speaker was not binaurally rendered to both ears");
        if (kAz[speaker] < 0) require(left > right, "left speaker mapped to wrong ear");
        if (kAz[speaker] > 0) require(right > left, "right speaker mapped to wrong ear");
        for (int frame = 0; frame < renderer.latencyFrames(); ++frame)
            require(output[frame * 2] == 0 && output[frame * 2 + 1] == 0,
                    "input streaming latency is missing");
        std::cout << "speaker " << speaker << ": " << left << " / " << right << '\n';
    }
    spatial::OpenAlRenderer horizontal(kRate), elevated(kRate);
    std::vector<float> input(kFrames * 8);
    input[2] = 0.5f;
    auto up = kEl;
    up[2] = 60.0f;
    const auto a = render(horizontal, input, {256});
    const auto b = render(elevated, input, {256}, kAz, up);
    require(difference(a, b) > 1e-4, "elevation did not change HRTF impulse response");
}

void continuityAndReset() {
    std::vector<float> input(kFrames * 8);
    for (int frame = 0; frame < kFrames - 512; ++frame)
        for (int channel = 0; channel < 8; ++channel)
            input[frame * 8 + channel] = 0.015f * std::sin(
                frame * (0.04f + channel * 0.013f));
    spatial::OpenAlRenderer renderer(kRate);
    require(renderer.hrtfName() == "Built-In HRTF", "unexpected OpenAL HRTF");
    const auto baseline = render(renderer, input, {kFrames});
    renderer.reset();
    const auto chunked = render(renderer, input, {1, 13, 127, 3, 509, 64});
    require(difference(baseline, chunked) < 2e-5, "chunk boundaries changed continuous OpenAL output");
    renderer.reset();
    const auto silence = render(renderer, std::vector<float>(input.size()), {31});
    require(energy(silence, 0) + energy(silence, 1) < 1e-20, "reset retained previous HRTF tail");

    // Distinct contexts may be interleaved on the same rendering thread.
    spatial::OpenAlRenderer first(kRate), second(kRate);
    const auto one = render(first, input, {17});
    const auto two = render(second, input, {257});
    require(difference(one, two) < 2e-5, "thread-local contexts interfered");
}

void layoutAndRate() {
    for (int rate : {8000, 44100, 48000, 96000}) {
        spatial::OpenAlRenderer renderer(rate);
        std::vector<float> input(kFrames * 8);
        input[3] = 1.0f;
        const auto output = render(renderer, input, {1, 511});
        require(energy(output, 0) + energy(output, 1) == 0.0, "LFE unexpectedly entered HRTF sources");
    }
    bool rejected = false;
    try { spatial::OpenAlRenderer bad(7999); } catch (const std::invalid_argument&) { rejected = true; }
    require(rejected, "invalid sample rate accepted");
    spatial::OpenAlRenderer renderer(kRate);
    renderer.render(nullptr, nullptr, 0, kAz, kEl);
    rejected = false;
    try { renderer.render(nullptr, nullptr, 1, kAz, kEl); }
    catch (const std::invalid_argument&) { rejected = true; }
    require(rejected, "null PCM accepted");
}
}

int main() {
    try {
        directionTests();
        continuityAndReset();
        layoutAndRate();
        std::cout << "OpenAL HRTF, 7 sources, elevation, chunk continuity, reset, rates and LFE: PASS\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "OpenAL test failure: " << error.what() << '\n';
        return 1;
    }
}
