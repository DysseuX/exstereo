/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "ffmpeg_filters.h"
#include "spatial_engine.h"
#include <iostream>
#include <exception>
extern "C" {
#include <mysofa.h>
}

// Argument must be readable HDF5 with invalid SOFA metadata, not a missing file.
// This catches libmysofa's positive error code being mistaken for AVFilter success.
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    int error = 0;
    auto* hrtf = mysofa_load(argv[1], &error);
    if (!hrtf || error) {
        if (hrtf) mysofa_free(hrtf);
        std::cerr << "FAIL fixture must load successfully before validation\n";
        return 3;
    }
    error = mysofa_check(hrtf);
    mysofa_free(hrtf);
    if (error <= 0) {
        std::cerr << "FAIL fixture must produce a positive mysofa_check error\n";
        return 4;
    }
    spatial::Settings settings;
    try {
        spatial::SofaRenderer renderer(48000, argv[1], settings.azimuths, settings.elevations);
    } catch (const std::exception& error) {
        std::cout << "PASS invalid SOFA rejected: " << error.what() << '\n';
        return 0;
    }
    std::cerr << "FAIL invalid SOFA accepted\n";
    return 1;
}
