/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once

#include <memory>

namespace spatial {

// Streaming complex-covariance Wiener primary/ambient decomposition and 7.1
// synthesis. Model and rendering choices: PAE_PAD.md.
class PaeUpmixer {
public:
    explicit PaeUpmixer(int sampleRate);
    ~PaeUpmixer();
    PaeUpmixer(const PaeUpmixer&) = delete;
    PaeUpmixer& operator=(const PaeUpmixer&) = delete;

    int latencyFrames() const;

    // Always consumes and produces frames. Input is interleaved L/R; output is
    // FL FR FC LFE BL BR SL SR. Arbitrary chunk sizes are supported. Buffers must
    // not overlap. Feed latencyFrames() zeros at EOF, discard initial latency,
    // and retain the original frame count. Filter/reverb tails are not latency.
    // Optional diagnostics are interleaved P_L P_R A_L A_R at the same latency;
    // before 7.1 rendering, P_L+A_L and P_R+A_R reconstruct the delayed input.
    void process(const float* stereo, float* wave71, int frames,
                 float* primaryAmbient = nullptr);
    void reset();

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace spatial
