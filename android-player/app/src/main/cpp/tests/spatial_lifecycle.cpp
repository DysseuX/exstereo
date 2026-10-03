/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

// Exercise SOFA validation/release while a separate engine keeps rendering.
#include "spatial_engine.h"
#include <atomic>
#include <cmath>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    try {
        spatial::Engine playback(48000, argv[1]);
        spatial::Settings settings;
        settings.mode = 2;
        std::atomic<bool> begin{false};
        std::exception_ptr backgroundError;
        std::thread importer([&] {
            while (!begin.load()) std::this_thread::yield();
            try {
                for (int i = 0; i < 3; ++i) {
                    spatial::Engine validation(48000, argv[1]);
                    validation.process(std::vector<float>(512, 0.01f).data(), 256, 2, settings);
                }
            } catch (...) { backgroundError = std::current_exception(); }
        });
        begin.store(true);
        std::exception_ptr renderError;
        try {
            std::vector<float> input(256 * 8, 0.0f);
            for (int frame = 0; frame < 256; ++frame)
                input[frame * 8] = 0.02f * std::sin(frame * 0.15f);
            double energy = 0;
            for (int block = 0; block < 300; ++block) {
                const auto output = playback.process(input.data(), 256, 8, settings);
                for (float sample : output) {
                    if (!std::isfinite(sample) || std::abs(sample) > 0.981f)
                        throw std::runtime_error("Invalid samples during background SOFA validation");
                    energy += sample * sample;
                }
            }
            if (energy < 1e-6) throw std::runtime_error("Playback became silent");
        } catch (...) { renderError = std::current_exception(); }
        importer.join();
        if (backgroundError) std::rethrow_exception(backgroundError);
        if (renderError) std::rethrow_exception(renderError);
        std::cout << "PASS: SOFA initialization/release alongside continuous eight-channel rendering\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
