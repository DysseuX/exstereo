/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "spatial_engine.h"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool valid, const char* message) {
    if (!valid) throw std::runtime_error(message);
}
std::vector<float> render(spatial::Engine& engine, std::vector<float> input, int channels,
                          const spatial::Settings& settings, bool fragmented = false) {
    const auto originalFrames = input.size() / channels;
    input.resize(input.size() + engine.latencyFrames() * channels, 0);
    std::vector<float> result;
    const int chunks[] = {1, 17, 256, 997, 13, 2048, 89};
    size_t offset = 0;
    int index = 0;
    while (offset < input.size() / channels) {
        const auto frames = std::min<size_t>(fragmented ? chunks[index++ % 7] : input.size(), input.size() / channels - offset);
        const auto part = engine.process(input.data() + offset * channels, static_cast<int>(frames), channels, settings);
        require(part.size() == frames * 2, "process changed frame count");
        result.insert(result.end(), part.begin(), part.end());
        offset += frames;
    }
    result.erase(result.begin(), result.begin() + engine.latencyFrames() * 2);
    result.resize(originalFrames * 2);
    for (float sample : result)
        require(std::isfinite(sample) && std::abs(sample) <= 0.981f, "non-finite or clipped output");
    return result;
}
double energy(const std::vector<float>& x, int channel) {
    double sum = 0;
    for (size_t i = channel; i < x.size(); i += 2) sum += double(x[i]) * x[i];
    return sum;
}
void close(const std::vector<float>& a, const std::vector<float>& b, float epsilon = 1e-6f) {
    require(a.size() == b.size(), "different lengths");
    for (size_t i = 0; i < a.size(); ++i)
        if (std::abs(a[i] - b[i]) > epsilon) throw std::runtime_error("sample mismatch at " + std::to_string(i));
}
}

int main(int argc, char** argv) {
    try {
        const std::string sofa = argc > 1 ? argv[1] : "";
        spatial::Engine engine(48000, sofa);
        require(engine.latencyFrames() == 1536, "unexpected buffering delay");
        spatial::Settings settings;
        settings.mode = 0;
        std::vector<float> source(6001 * 2);
        for (size_t f = 0; f < source.size() / 2; ++f) {
            source[f * 2] = 0.15f * std::sin(float(f) * 0.037f);
            source[f * 2 + 1] = 0.12f * std::cos(float(f) * 0.052f);
        }
        close(source, render(engine, source, 2, settings, true));
        engine.reset();
        close(source, render(engine, source, 2, settings));
        engine.reset();
        close(std::vector<float>{0.2f, -0.1f}, render(engine, {0.2f, -0.1f}, 2, settings, true));

        settings.mode = 1;
        settings.width = 1;
        engine.reset();
        close(source, render(engine, source, 2, settings, true));
        std::vector<float> mono(6001, 0.1f);
        settings.width = 2;
        engine.reset();
        const auto wideMono = render(engine, mono, 1, settings, true);
        for (float x : wideMono) require(std::abs(x - 0.1f) < 1e-6f, "width changed mono center");

        settings.mode = 2;
        for (int channels : {1, 2, 6, 8}) {
            std::vector<float> input(7003 * channels);
            for (size_t i = 0; i < input.size(); ++i) input[i] = 0.015f * std::sin(float(i) * 0.07f);
            engine.reset();
            const auto whole = render(engine, input, channels, settings);
            engine.reset();
            close(whole, render(engine, input, channels, settings, true));
            require(energy(whole, 0) + energy(whole, 1) > 1e-5, "spatial render is silent");
        }

        std::vector<float> lfe(4001 * 8, 0);
        for (size_t f = 0; f < lfe.size() / 8; ++f) lfe[f * 8 + 3] = 0.2f;
        engine.reset();
        const auto low = render(engine, lfe, 8, settings, true);
        for (float x : low) require(std::abs(x - 0.2f * 0.70710678f * 0.5f) < 1e-5f, "LFE routing or unwanted +10 dB boost");

        // Every full-range WAVE channel must reach its assigned movable source.
        const int waveChannels[] = {0, 1, 2, 4, 5, 6, 7};
        for (int speaker = 0; speaker < 7; ++speaker) {
            std::vector<float> input(4096 * 8, 0);
            for (int f = 0; f < 4096; ++f)
                input[f * 8 + waveChannels[speaker]] = 0.03f * std::sin(float(f) * 0.21f);
            settings.azimuths[speaker] = -90;
            engine.reset();
            const auto left = render(engine, input, 8, settings);
            require(energy(left, 0) > energy(left, 1) * 1.05, "source does not pan to left ear");
            settings.azimuths[speaker] = 90;
            engine.reset();
            const auto right = render(engine, input, 8, settings);
            require(energy(right, 1) > energy(right, 0) * 1.05, "source does not pan to right ear");
        }

        source[100] = std::numeric_limits<float>::quiet_NaN();
        source[101] = std::numeric_limits<float>::infinity();
        source[200] = 1000;
        engine.reset();
        render(engine, source, 2, settings, true);
        bool rejected = false;
        try { engine.process(source.data(), 1, 3, settings); }
        catch (const std::invalid_argument&) { rejected = true; }
        require(rejected, "unsupported layout accepted");
        rejected = false;
        try { spatial::Engine invalid(48000, "/nonexistent/spatial-test.sofa"); }
        catch (const std::runtime_error&) { rejected = true; }
        require(rejected, "missing SOFA silently used default HRTF");
        std::cout << "PASS: latency, exact bypass, short EOS, width, mono, arbitrary chunks, 1/2/6/8 channels, seven source positions, LFE, finite limiter, reset, invalid layout/SOFA" << std::endl;
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << std::endl;
        return 1;
    }
}
