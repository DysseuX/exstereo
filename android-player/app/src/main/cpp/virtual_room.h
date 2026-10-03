/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once
#include <array>
#include <memory>

namespace spatial {
struct RoomSettings {
    bool enabled = false;
    int activeSources = 7;
    float mix = .15f, size = 5, damping = .5f, decay = .35f;
    float yaw = 0, pitch = 0;
    std::array<float, 7> azimuths{{-30,30,0,-150,150,-90,90}};
    std::array<float, 7> elevations{};
    std::array<float, 7> distances{{1,1,1,1,1,1,1}};
};

// Six first-order image sources per speaker and a damped four-line FDN.
// Reflection ears use analytic ITD/ILD/head shadow, not measured HRTF/BRIR.
// Returns only the wet send: sum it with the direct renderer before limiting.
// Seven interleaved sources; stereo output, nonoverlapping buffers, any chunks.
// Effect tails add no transport latency and are truncated to track length.
class VirtualRoom {
public:
    explicit VirtualRoom(int sampleRate);
    ~VirtualRoom();
    void process(const float* sources, float* output, int frames, const RoomSettings& settings);
    void reset();
private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};
}
