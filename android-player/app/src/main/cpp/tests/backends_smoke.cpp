/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "spatial_engine.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr float kSqrtHalf = .7071067811865475244f;
const char* kRenderers[] = {"Steam", "OpenAL", "sofalizer"};
const char* kUpmixers[] = {"FreeSurround", "PAE", "surround"};
std::string context;
void require(bool ok, const std::string& reason) {
    if (!ok) throw std::runtime_error(context + ": " + reason);
}

std::vector<float> render(spatial::Engine& engine, std::vector<float> input, int channels,
                          const spatial::Settings& settings, bool fragmented = false) {
    const size_t frames = input.size() / channels;
    const int latency = engine.latencyFrames();
    require(latency > 0 && latency < 8192, "unreasonable buffering delay");
    input.resize(input.size() + size_t(latency) * channels, 0);
    std::vector<float> result;
    const int sizes[] = {1, 17, 255, 997, 3, 1024, 89};
    int index = 0;
    size_t offset = 0;
    while (offset < input.size() / channels) {
        const int count = int(std::min(input.size() / channels - offset,
            fragmented ? size_t(sizes[index++ % 7]) : input.size() / channels));
        const auto block = engine.process(input.data() + offset * channels, count, channels, settings);
        require(block.size() == size_t(count) * 2, "process changed frame count");
        result.insert(result.end(), block.begin(), block.end());
        offset += count;
    }
    result.erase(result.begin(), result.begin() + latency * 2);
    require(result.size() == frames * 2, "EOS changed duration");
    for (float value : result)
        require(std::isfinite(value) && std::abs(value) <= .98001f, "non-finite or unlimited PCM");
    return result;
}

void close(const std::vector<float>& actual, const std::vector<float>& expected,
           float tolerance, const char* why) {
    require(actual.size() == expected.size(), std::string(why) + ": sample count");
    for (size_t i = 0; i < actual.size(); ++i)
        require(std::abs(actual[i] - expected[i]) <= tolerance,
                std::string(why) + " at sample " + std::to_string(i));
}

double energy(const std::vector<float>& stereo, int channel, int skip = 0) {
    double result = 0;
    for (size_t f = skip; f < stereo.size() / 2; ++f)
        result += double(stereo[f * 2 + channel]) * stereo[f * 2 + channel];
    return result;
}

std::vector<float> signal(int frames, int channels) {
    std::vector<float> result(size_t(frames) * channels);
    for (int f = 0; f < frames; ++f)
        for (int c = 0; c < channels; ++c)
            result[f * channels + c] = .012f * std::sin(float(f) * (.061f + c * .017f)) +
                                       .009f * std::cos(float(f) * (.151f + c * .013f));
    return result;
}

std::string profileFor(int renderer, const std::string& sofa) { return renderer == 1 ? "" : sofa; }

void testMatrix(const std::string& sofa, int rate) {
    std::array<std::vector<float>, 3> readyReferences;
    for (int renderer = 0; renderer < 3; ++renderer) {
        for (int upmix = 0; upmix < 3; ++upmix) {
            context = std::string(kRenderers[renderer]) + "/" + kUpmixers[upmix] + "/" + std::to_string(rate);
            spatial::Settings settings;
            spatial::Engine engine(rate, profileFor(renderer, sofa), renderer, upmix, settings);
            const auto stereo = signal(3073, 2);

            settings.mode = 0;
            close(render(engine, stereo, 2, settings, true), stereo, 1e-6f, "dry latency");
            for (int frames : {1, 17, 1023, 1025}) {
                engine.reset();
                const auto shortInput = signal(frames, 2);
                close(render(engine, shortInput, 2, settings, true), shortInput, 1e-6f, "short dry EOS");
            }

            settings.mode = 1;
            settings.width = 1;
            engine.reset();
            close(render(engine, stereo, 2, settings), stereo, 1e-6f, "unity width");
            const auto mono = signal(1537, 1);
            std::vector<float> duplicated(mono.size() * 2);
            for (size_t f = 0; f < mono.size(); ++f) duplicated[f * 2] = duplicated[f * 2 + 1] = mono[f];
            settings.width = 2;
            engine.reset();
            close(render(engine, mono, 1, settings, true), duplicated, 1e-6f, "mono width");

            settings.mode = 2;
            engine.reset();
            const auto whole = render(engine, stereo, 2, settings);
            require(energy(whole, 0) + energy(whole, 1) > 1e-7, "silent spatial stereo");
            engine.reset();
            close(render(engine, stereo, 2, settings, true), whole, 3e-6f, "spatial chunk/reset invariance");
            engine.reset();
            const auto monoSpatial = render(engine, mono, 1, settings, true);
            require(energy(monoSpatial, 0) + energy(monoSpatial, 1) > 1e-7, "silent spatial mono");
            engine.reset();
            render(engine, {.1f, -.07f}, 2, settings, true); // duration/finite for one-frame spatial EOS

            // Ready PCM must bypass every selected stereo upmixer. Compare the
            // rendered waveform across upmixer choices after reported latency.
            engine.reset();
            auto ready8 = signal(3073, 8);
            const auto readyOutput = render(engine, ready8, 8, settings, true);
            if (upmix == 0) readyReferences[renderer] = readyOutput;
            else close(readyOutput, readyReferences[renderer], 4e-5f, "ready 7.1 was re-upmixed or misaligned");

            // The supported 5.1 input order is FL FR FC LFE BL BR; 7.1 simply
            // appends SL/SR. Padding those two channels must preserve playback.
            const auto ready6 = signal(2049, 6);
            std::vector<float> padded6(size_t(2049) * 8, 0);
            for (int f = 0; f < 2049; ++f)
                std::copy_n(ready6.data() + f * 6, 6, padded6.data() + f * 8);
            engine.reset();
            const auto six = render(engine, ready6, 6, settings, true);
            engine.reset();
            close(render(engine, padded6, 8, settings), six, 3e-6f, "5.1/7.1 WAVE order mismatch");

            std::vector<float> lfe(1537 * 8, 0), expectedLfe(1537 * 2);
            for (int f = 0; f < 1537; ++f) {
                lfe[f * 8 + 3] = .02f * std::sin(.19f * f);
                expectedLfe[f * 2] = expectedLfe[f * 2 + 1] = .5f * kSqrtHalf * lfe[f * 8 + 3];
            }
            engine.reset();
            close(render(engine, lfe, 8, settings, true), expectedLfe, 1e-6f,
                  "LFE duplicated, HRTF-filtered, boosted, or delayed incorrectly");

            auto malformed = signal(1025, 2);
            malformed[0] = std::numeric_limits<float>::quiet_NaN();
            malformed[1] = std::numeric_limits<float>::infinity();
            malformed[2] = std::numeric_limits<float>::max();
            engine.reset();
            render(engine, malformed, 2, settings, true);
            std::cout << "PASS " << context << ": dry/width/EOS/chunks/mono/5.1/7.1 bypass/LFE/finite\n";
        }
    }
}

void testEverySpeaker(const std::string& sofa, int rate) {
    const int waveChannels[] = {0, 1, 2, 4, 5, 6, 7};
    for (int renderer = 0; renderer < 3; ++renderer) {
        for (float azimuth : {-90.0f, 90.0f}) {
            spatial::Settings settings;
            settings.surroundGain = 1;
            settings.azimuths.fill(azimuth);
            // sofalizer captures the coordinates at construction.
            spatial::Engine engine(rate, profileFor(renderer, sofa), renderer, 0, settings);
            for (int speaker = 0; speaker < 7; ++speaker) {
                context = std::string(kRenderers[renderer]) + "/speaker" + std::to_string(speaker) +
                          "/az" + std::to_string(int(azimuth)) + "/" + std::to_string(rate);
                auto mono = signal(4097, 1);
                std::vector<float> ready(mono.size() * 8, 0);
                for (size_t f = 0; f < mono.size(); ++f) ready[f * 8 + waveChannels[speaker]] = mono[f];
                engine.reset();
                const auto output = render(engine, ready, 8, settings, true);
                const double left = energy(output, 0, 256), right = energy(output, 1, 256);
                require(azimuth < 0 ? left > right * 1.05 : right > left * 1.05,
                        "speaker position/channel ignored or mirrored");
            }
        }
    }
    std::cout << "PASS all seven movable speakers for all renderers at " << rate << " Hz\n";
}
} // namespace

int main(int argc, char** argv) {
    try {
        if (argc < 2 || argc > 3) {
            std::cerr << "usage: backends_smoke PROFILE.sofa [sample-rate=48000]\n";
            return 2;
        }
        const int rate = argc == 3 ? std::stoi(argv[2]) : 48000;
        testMatrix(argv[1], rate);
        testEverySpeaker(argv[1], rate);
        std::cout << "PASS 3 renderers x 3 upmixers, sample rate " << rate << " Hz\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
