/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "ffmpeg_filters.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr int block = 1024;
using Block = std::array<float, block * 2>;
void require(bool condition, const std::string& description) {
    if (!condition) throw std::runtime_error(description);
}
void near(float actual, double expected, const std::string& context) {
    require(std::isfinite(actual) && std::abs(actual - expected) < 0.000001,
            context + ": actual=" + std::to_string(actual) + " expected=" + std::to_string(expected));
}

void referenceCheck(int rate, int algorithm) {
    constexpr int blocks = 6;
    constexpr int frames = blocks * block;
    std::vector<float> input(frames * 2);
    for (int i = 0; i < frames; ++i) {
        // Distinct channels, nonperiodic at block boundaries, silence then an
        // onset, and a final silence exercise both delayed paths and tails.
        if (i < 11 || i > frames - block) continue;
        input[i * 2] = 0.14f * std::sin(0.071f * i) + (i == 1021 ? .21f : 0.f);
        input[i * 2 + 1] = 0.11f * std::cos(0.043f * i) - (i == 2046 ? .24f : 0.f);
    }
    auto sample = [&input](int frame, int channel) {
        return frame >= 0 ? input[frame * 2 + channel] : 0.f;
    };
    auto mid = [&sample](int frame) {
        return (static_cast<double>(sample(frame, 0)) + sample(frame, 1)) * .5;
    };
    // Reference equations correspond to upstream FFmpeg's documented filter
    // models, independently of graph plumbing and float/packed conversions.
    const int delay = static_cast<int>(std::lrint(12.f * rate / 1000.f));
    const int delayLeft = static_cast<int>(2.05 * .001 * rate);
    const int delayRight = static_cast<int>(2.12 * .001 * rate);
    spatial::FfmpegWidener processor(rate, algorithm);
    Block output{};
    double change = 0;
    for (int part = 0; part < blocks; ++part) {
        processor.process(input.data() + part * block * 2, output.data(), block);
        for (int n = 0; n < block; ++n) {
            const int i = part * block + n;
            const float left = sample(i, 0), right = sample(i, 1);
            double expectedLeft, expectedRight;
            if (algorithm == 1) {
                const float average = (left + right) * .5f;
                expectedLeft = average + 2.f * (left - average);
                expectedRight = average + 2.f * (right - average);
            } else if (algorithm == 2) {
                expectedLeft = left - .15f * right - .15f * sample(i - delay, 1);
                expectedRight = right - .15f * left - .15f * sample(i - delay, 0);
            } else {
                expectedLeft = mid(i) - .35 * mid(i - delayRight);
                expectedRight = mid(i) + .35 * mid(i - delayLeft);
            }
            const std::string description = "filter=" + std::to_string(algorithm) +
                " rate=" + std::to_string(rate) + " frame=" + std::to_string(i);
            near(output[n * 2], expectedLeft, description + " left");
            near(output[n * 2 + 1], expectedRight, description + " right");
            change += std::abs(output[n * 2] - left) + std::abs(output[n * 2 + 1] - right);
        }
    }
    require(change > 1, "filter did not change stereo signal");

    // A new stream/seek must never emit the previous track's delayed samples.
    Block impulse{}, silence{};
    impulse[(block - 1) * 2] = .5f;
    processor.process(impulse.data(), output.data(), block);
    processor.reset();
    processor.process(silence.data(), output.data(), block);
    for (float value : output) require(value == 0, "reset leaked an old delayed sample");

    bool rejected = false;
    try { processor.process(silence.data(), output.data(), block - 1); }
    catch (const std::invalid_argument&) { rejected = true; }
    require(rejected, "accepted wrong block size");
    rejected = false;
    try { processor.process(nullptr, output.data(), block); }
    catch (const std::invalid_argument&) { rejected = true; }
    require(rejected, "accepted null PCM input");
}

void monoAndHeadroom() {
    Block input{}, output{};
    spatial::FfmpegWidener extra(48000, 1);
    for (int i = 0; i < block; ++i) input[i * 2] = input[i * 2 + 1] = .3f * std::sin(i * .1f);
    extra.process(input.data(), output.data(), block);
    require(input == output, "extrastereo must leave exact mono unchanged");
    input.fill(0);
    input[0] = 1;
    input[1] = -1;
    extra.process(input.data(), output.data(), block);
    require(output[0] == 2 && output[1] == -2, "extrastereo added unwanted hard clipping");

    spatial::FfmpegWidener haas(48000, 3);
    input.fill(0);
    input[0] = input[1] = .5f;
    haas.process(input.data(), output.data(), block);
    require(output[0] == .5f && output[1] == .5f, "Haas delayed the entire direct signal");
    double difference = 0;
    for (int i = 0; i < block; ++i) difference += std::abs(output[i * 2] - output[i * 2 + 1]);
    require(difference > .1, "Haas did not create stereo from mono");
    haas.reset();
    input.fill(0);
    input[0] = .5f;
    input[1] = -.5f;
    haas.process(input.data(), output.data(), block);
    for (float value : output) require(value == 0, "documented MID-only Haas preset unexpectedly retains SIDE");
}
}

int main() {
    try {
        for (int rate : {8000, 44100, 48000, 96000})
            for (int algorithm = 1; algorithm <= 3; ++algorithm) referenceCheck(rate, algorithm);
        monoAndHeadroom();
        for (int invalid : {0, 4, -1}) {
            bool rejected = false;
            try { spatial::FfmpegWidener processor(48000, invalid); }
            catch (const std::invalid_argument&) { rejected = true; }
            require(rejected, "accepted invalid algorithm");
        }
        for (int invalid : {0, 7999, 96001}) {
            bool rejected = false;
            try { spatial::FfmpegWidener processor(invalid, 1); }
            catch (const std::invalid_argument&) { rejected = true; }
            require(rejected, "accepted invalid sample rate");
        }
        std::cout << "PASS FFmpeg widening: 3 real filters, 4 rates, independent equations, "
                     "cross-block tails, reset, mono, headroom, exact frame count\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
