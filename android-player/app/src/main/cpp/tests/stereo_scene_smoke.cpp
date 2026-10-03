/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "spatial_engine.h"

#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::string context;
void require(bool good, const std::string& why) {
    if (!good) throw std::runtime_error(context + ": " + why);
}
std::vector<float> signal(int frames = 8193) {
    std::vector<float> input(size_t(frames) * 2);
    for (int f = 0; f < frames; ++f) {
        input[f * 2] = .014f * std::sin(.079f * f) + .007f * std::cos(.711f * f);
        input[f * 2 + 1] = .013f * std::sin(.117f * f) + .009f * std::cos(.413f * f);
    }
    return input;
}
std::vector<float> render(spatial::Engine& engine, std::vector<float> input,
                          const spatial::Settings& settings, bool fragment = false) {
    const size_t originalFrames = input.size() / 2;
    const int latency = engine.latencyFrames();
    input.resize(input.size() + size_t(latency) * 2);
    std::vector<float> output;
    size_t offset = 0;
    const int sizes[] = {1, 255, 17, 1024, 73, 997};
    int chunk = 0;
    while (offset < input.size() / 2) {
        const int frames = int(std::min(input.size() / 2 - offset,
            fragment ? size_t(sizes[chunk++ % 6]) : input.size() / 2));
        auto block = engine.process(input.data() + offset * 2, frames, 2, settings);
        require(block.size() == size_t(frames) * 2, "chunk duration changed");
        output.insert(output.end(), block.begin(), block.end());
        offset += frames;
    }
    output.erase(output.begin(), output.begin() + latency * 2);
    require(output.size() == originalFrames * 2, "EOS duration changed");
    for (float sample : output) require(std::isfinite(sample) && std::abs(sample) <= .98001f, "invalid PCM");
    return output;
}
double energy(const std::vector<float>& output, int ear) {
    double result = 0;
    for (size_t i = 1024 * 2 + ear; i < output.size(); i += 2) result += double(output[i]) * output[i];
    return result;
}
void close(const std::vector<float>& a, const std::vector<float>& b, float tolerance, const std::string& why) {
    require(a.size() == b.size(), why + " duration");
    for (size_t i = 0; i < a.size(); ++i)
        require(std::abs(a[i] - b[i]) < tolerance, why + " at " + std::to_string(i));
}
void testScene(int renderer, const std::string& sofa) {
    context = "renderer=" + std::to_string(renderer);
    spatial::Settings s;
    s.mode = 0;
    s.virtualStereo = true;
    s.stereoAzimuths = {{-90, 90}};
    const auto input = signal();
    spatial::Engine engine(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
    const auto base = render(engine, input, s);
    engine.reset();
    close(render(engine, input, s, true), base, 4e-6f, "virtual stereo reset/chunk invariance");
    for (int channel = 0; channel < 2; ++channel) {
        auto isolated = input;
        for (size_t f = 0; f < isolated.size() / 2; ++f) isolated[f * 2 + (1 - channel)] = 0;
        engine.reset();
        auto output = render(engine, isolated, s);
        require(energy(output, channel) > energy(output, 1 - channel) * 1.05,
                "stereo channel is not an independent correctly positioned mono source");
    }
    s.mode = 1;
    s.width = 1;
    engine.reset();
    close(render(engine, input, s), base, 4e-6f, "unity width before binaural rendering");
    s.width = 1.8f;
    engine.reset();
    auto wide = render(engine, input, s);
    double difference = 0;
    for (size_t i = 0; i < wide.size(); ++i) difference += std::abs(wide[i] - base[i]);
    require(difference > .01, "width control did not reach virtual stereo sources");

    s.mode = 0;
    s.stereoDistances = {{4, 4}};
    engine.reset();
    auto far = render(engine, input, s);
    require(energy(base, 0) + energy(base, 1) > 10 * (energy(far, 0) + energy(far, 1)),
            "distance gain/air loss ignored");
    s.stereoDistances = {{1, 1}};
    s.hrtfBlend = 0;
    engine.reset();
    close(render(engine, input, s), input, 3e-6f, "HRTF blend zero must give matching equal-power hard-pan sources");
    s.hrtfGainDb = -3;
    s.hrtfBalanceDb = 6;
    auto expected = input;
    for (size_t f = 0; f < expected.size() / 2; ++f) expected[f * 2] *= std::pow(10.0f, -6.0f / 20);
    engine.reset();
    close(render(engine, input, s), expected, 3e-6f, "virtual gain/balance calibration");
    s.virtualStereo = false;
    engine.reset();
    close(render(engine, input, s), input, 3e-6f, "virtual calibration modified direct bypass");
    std::cout << "PASS " << context << ": independent pair, width, distance, blend, balance/gain, bypass, reset, chunks, EOS\n";
}
void testOrientation(int renderer, const std::string& sofa) {
    context = "orientation/renderer=" + std::to_string(renderer);
    spatial::Settings s;
    s.mode = 0;
    s.virtualStereo = true;
    s.stereoAzimuths = {{0, 0}};
    s.listenerYaw = 90;
    const auto input = signal();
    spatial::Engine lookingRight(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
    const auto output = render(lookingRight, input, s);
    require(energy(output, 0) > energy(output, 1) * 1.05, "looking right must put front speakers at the listener's left");
    s.listenerYaw = 0;
    s.stereoAzimuths = {{-90, -90}};
    spatial::Engine speakersLeft(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
    close(render(speakersLeft, input, s), output, 4e-5f, "inverse listener yaw geometry");

    s.stereoAzimuths = {{0, 0}};
    s.stereoElevations = {{30, 30}};
    s.listenerPitch = 30;
    spatial::Engine lookingUp(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
    const auto level = render(lookingUp, input, s);
    s.stereoElevations = {{0, 0}};
    s.listenerPitch = 0;
    spatial::Engine facingLevel(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
    close(render(facingLevel, input, s), level, 4e-5f, "inverse listener pitch geometry");
    std::cout << "PASS " << context << ": yaw/pitch inverse 3D rotation\n";
}

// Keep one engine and continuous PCM throughout these changes: a test that
// recreates or resets the engine cannot detect controls sampled only at init.
void testLiveScene(int renderer, const std::string& sofa) {
    context = "live/renderer=" + std::to_string(renderer);
    spatial::Settings s;
    s.mode = 0;
    s.virtualStereo = true;
    s.stereoAzimuths = {{-90, 90}};
    spatial::Engine engine(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
    std::vector<float> input(1024 * 2);
    constexpr float pi = 3.14159265358979323846f;
    for (int f = 0; f < 1024; ++f) {
        // Integer cycles per block give stationary, phase-identical windows
        // after settling; the right virtual source remains silent.
        input[f * 2] = .014f * std::sin(2 * pi * 31 * f / 1024) +
                       .009f * std::cos(2 * pi * 157 * f / 1024);
    }
    const auto steady = [&] {
        std::vector<float> last;
        // 1.36 s of samples, not a wall-clock sleep; >16 position time constants.
        for (int block = 0; block < 64; ++block) {
            last = engine.process(input.data(), 1024, 2, s);
            require(last.size() == input.size(), "live update changed frame count");
            for (float sample : last)
                require(std::isfinite(sample) && std::abs(sample) <= .98001f, "invalid live PCM");
        }
        return last;
    };
    const auto blockEnergy = [](const std::vector<float>& output, int ear) {
        double sum = 0;
        for (size_t i = ear; i < output.size(); i += 2) sum += double(output[i]) * output[i];
        return sum;
    };
    const auto totalEnergy = [&](const std::vector<float>& output) {
        return blockEnergy(output, 0) + blockEnergy(output, 1);
    };
    auto left = steady();
    require(blockEnergy(left, 0) > 1.05 * blockEnergy(left, 1), "initial left position not audible");
    s.stereoAzimuths[0] = 90;
    auto right = steady();
    require(blockEnergy(right, 1) > 1.05 * blockEnergy(right, 0), "live azimuth did not move the source right");
    s.stereoElevations[0] = 60;
    auto raised = steady();
    double elevationDifference = 0;
    for (size_t i = 0; i < raised.size(); ++i) {
        const double delta = raised[i] - right[i];
        elevationDifference += delta * delta;
    }
    require(elevationDifference > totalEnergy(right) * .0001,
            "live elevation did not change the rendered HRIR");
    s.stereoDistances[0] = 4;
    auto far = steady();
    require(totalEnergy(raised) > totalEnergy(far) * 10,
            "live distance did not reduce the source level");
    s.hrtfGainDb = -6;
    auto quieter = steady();
    const double ratio = totalEnergy(quieter) / totalEnergy(far);
    require(ratio > .23 && ratio < .27, "live gain calibration was not applied (-6 dB)");
    s.hrtfBalanceDb = 6;
    auto balanced = steady();
    const double leftRatio = blockEnergy(balanced, 0) / blockEnergy(quieter, 0);
    const double rightRatio = blockEnergy(balanced, 1) / blockEnergy(quieter, 1);
    require(leftRatio > .48 && leftRatio < .53 && rightRatio > 1.92 && rightRatio < 2.06,
            "live ear-balance calibration was not applied");
    std::cout << "PASS " << context << ": continuous azimuth/elevation/distance/gain/balance updates without reset\n";
}
}
int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("usage: stereo_scene_smoke PROFILE.sofa");
        for (int renderer = 0; renderer < 3; ++renderer) {
            testScene(renderer, argv[1]);
            testOrientation(renderer, argv[1]);
            if (renderer < 2) testLiveScene(renderer, argv[1]);
        }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
