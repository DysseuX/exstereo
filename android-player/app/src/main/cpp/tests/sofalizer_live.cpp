/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "ffmpeg_filters.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <unistd.h>

namespace {
using Layout = std::array<float, 7>;
using Input = std::array<float, 1024 * 8>;
using Output = std::array<float, 1024 * 2>;
constexpr int block = 1024;
const Layout frontLeft{{-65, 30, 0, -150, 150, -90, 90}};
const Layout flat{};

void require(bool condition, const char* description) {
    if (!condition) throw std::runtime_error(description);
}
void finite(const Output& output) {
    for (float sample : output) require(std::isfinite(sample) && std::abs(sample) < 8.f,
                                       "non-finite or unbounded transition output");
}
void compare(const Output& actual, const Output& expected) {
    float difference = 0;
    for (size_t i = 0; i < actual.size(); ++i)
        difference = std::max(difference, std::abs(actual[i] - expected[i]));
    if (difference > 0.00001f)
        throw std::runtime_error("live layout differs from fixed reference by " + std::to_string(difference));
}
Output fixedOutput(int rate, const std::string& sofa, const Input& input, const Layout& az, const Layout& el) {
    spatial::SofaRenderer fixed(rate, sofa, az, el);
    Output output{};
    for (int i = 0; i < 8; ++i) fixed.render(input.data(), output.data(), block);
    return output;
}
void settle(spatial::SofaRenderer& renderer, const Input& input, Output& output, const Layout& az, const Layout& el) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    do {
        require(renderer.waitForPreparation(10000), "background preparation did not finish");
        renderer.render(input.data(), output.data(), block, az, el);
        finite(output);
        if (!renderer.isUpdating()) {
            // Also flush any profile tail before comparing two convolution
            // states. Bundled HRIRs fit comfortably within these eight blocks.
            for (int i = 0; i < 8; ++i) renderer.render(input.data(), output.data(), block, az, el);
            return;
        }
    } while (std::chrono::steady_clock::now() < deadline);
    throw std::runtime_error("coalesced layout never settled");
}
struct TemporarySofa {
    explicit TemporarySofa(const std::string& original) {
        char name[] = "/tmp/spatial-sofalizer-live-XXXXXX";
        const int descriptor = mkstemp(name);
        require(descriptor >= 0, "cannot create temporary SOFA");
        close(descriptor);
        path = name;
        std::ifstream source(original, std::ios::binary);
        std::ofstream target(path, std::ios::binary);
        target << source.rdbuf();
        require(source.good() && target.good(), "cannot copy SOFA test fixture");
    }
    ~TemporarySofa() { std::remove(path.c_str()); }
    std::string path;
};
}

int main(int argc, char** argv) {
    if (argc < 2 || argc > 3) {
        std::cerr << "usage: sofalizer_live PROFILE.sofa [sample-rate]\n";
        return 2;
    }
    try {
        const std::string sofa = argv[1];
        const int rate = argc == 3 ? std::stoi(argv[2]) : 48000;
        Input input{};
        // A periodic FL-only tone makes steady output independent of how many
        // blocks the background preparation takes; no audio-clock sleeps.
        for (int i = 0; i < block; ++i)
            input[i * 8] = 0.025f * std::sin(2.f * 3.14159265359f * 19.f * i / block);
        Output output{};
        spatial::SofaRenderer renderer(rate, sofa, frontLeft, flat);
        for (int i = 0; i < 8; ++i) renderer.render(input.data(), output.data(), block);
        compare(output, fixedOutput(rate, sofa, input, frontLeft, flat));

        auto target = frontLeft;
        target[0] = 65;
        settle(renderer, input, output, target, flat);
        compare(output, fixedOutput(rate, sofa, input, target, flat));
        require(renderer.lastUpdateError().empty(), "successful transition left an error");
        double left = 0, right = 0;
        for (int i = 0; i < block; ++i) {
            left += output[i * 2] * output[i * 2];
            right += output[i * 2 + 1] * output[i * 2 + 1];
        }
        require(right > left, "moving FL to the right did not move ear energy right");

        auto elevation = flat;
        for (int i = 0; i < 100; ++i) {
            target[0] = -80.f + i * 1.4f;
            elevation[0] = -30.f + i * 0.5f;
            renderer.render(input.data(), output.data(), block, target, elevation);
            finite(output);
        }
        target[0] = -43;
        elevation[0] = 21;
        settle(renderer, input, output, target, elevation);
        compare(output, fixedOutput(rate, sofa, input, target, elevation));
        // Fractional smoother steps under the integer lookup resolution must
        // not keep reloading an identical direction indefinitely.
        target[0] += 0.1f;
        renderer.render(input.data(), output.data(), block, target, elevation);
        require(!renderer.isUpdating(), "sub-degree jitter triggered another SOFA load");

        target[0] = 40;
        renderer.render(input.data(), output.data(), block, target, elevation);
        renderer.reset(); // Join an in-flight worker and adopt the latest layout.
        require(!renderer.isUpdating(), "reset left a queued transition");
        for (int i = 0; i < 8; ++i) renderer.render(input.data(), output.data(), block);
        compare(output, fixedOutput(rate, sofa, input, target, elevation));

        // A profile disappearing after initial loading must not interrupt the
        // active filter or cause a new load attempt for every audio block.
        TemporarySofa fixture(sofa);
        spatial::SofaRenderer failure(rate, fixture.path, frontLeft, flat);
        std::remove(fixture.path.c_str());
        settle(failure, input, output, target, elevation);
        require(!failure.lastUpdateError().empty(), "background load failure was not reported");
        compare(output, fixedOutput(rate, sofa, input, frontLeft, flat));
        for (int i = 0; i < 12; ++i) {
            failure.render(input.data(), output.data(), block, target, elevation);
            require(!failure.isUpdating(), "failed layout was retried without a new request");
        }
        std::cout << "PASS live sofalizer: fixed equivalence, moving source, coalescing, reset, safe failure; "
                  << rate << " Hz\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
