/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "spatial_engine.h"
#include "pae_upmixer.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::string context;
void require(bool ok, const std::string& message) {
    if (!ok) throw std::runtime_error(context + ": " + message);
}
std::vector<float> signal(int frames = 6145) {
    std::vector<float> input(size_t(frames) * 2);
    uint32_t noiseState = 0x875721d3u;
    const auto noise = [&noiseState]() {
        noiseState = noiseState * 1664525u + 1013904223u;
        return .004f * (float(noiseState >> 8) / 16777215.f - .5f);
    };
    for (int i = 0; i < frames; ++i) {
        // Independent broadband content gives the PAE covariance model a real
        // ambient component; narrowband coherent tones may correctly yield none.
        input[i * 2] = .013f * std::sin(.071f * i) + .011f * std::cos(.531f * i) + noise();
        input[i * 2 + 1] = .009f * std::cos(.091f * i) - .007f * std::sin(.713f * i) + noise();
    }
    return input;
}
void valid(const std::vector<float>& samples, size_t expectedSize) {
    require(samples.size() == expectedSize, "output duration changed");
    for (float value : samples) require(std::isfinite(value) && std::abs(value) <= .98001f, "invalid PCM");
}
void close(const std::vector<float>& actual, const std::vector<float>& expected, const char* message,
           float tolerance = 4e-6f) {
    require(actual.size() == expected.size(), std::string(message) + " duration");
    for (size_t i = 0; i < actual.size(); ++i)
        require(std::abs(actual[i] - expected[i]) <= tolerance,
                std::string(message) + " sample=" + std::to_string(i));
}
double difference(const std::vector<float>& a, const std::vector<float>& b) {
    require(a.size() == b.size(), "comparison duration changed");
    double sum = 0;
    for (size_t i = 0; i < a.size(); ++i) sum += std::abs(a[i] - b[i]);
    return sum;
}
double energy(const std::vector<float>& samples) {
    double sum = 0;
    for (float value : samples) sum += double(value) * value;
    return sum;
}
std::vector<float> render(spatial::Engine& engine, std::vector<float> input,
                          const spatial::Settings& settings, bool fragmented = false, int channels = 2) {
    const size_t originalSize = input.size() / channels * 2;
    const int latency = engine.latencyFrames();
    input.resize(input.size() + size_t(latency) * channels);
    std::vector<float> output;
    const int chunks[] = {1, 13, 255, 1024, 73, 997};
    size_t offset = 0;
    int chunk = 0;
    while (offset < input.size() / channels) {
        const int frames = int(std::min(input.size() / channels - offset,
            fragmented ? size_t(chunks[chunk++ % 6]) : input.size() / channels));
        auto block = engine.process(input.data() + offset * channels, frames, channels, settings);
        valid(block, size_t(frames) * 2);
        output.insert(output.end(), block.begin(), block.end());
        offset += frames;
    }
    output.erase(output.begin(), output.begin() + latency * 2);
    valid(output, originalSize);
    return output;
}
spatial::Settings settings(int algorithm) {
    spatial::Settings s;
    s.mode = 1;
    s.width = 1.65f;
    s.wideningAlgorithm = algorithm;
    s.wideningAmount = .65f;
    s.widenerLow = .3f;
    s.widenerHigh = .65f;
    s.transientProtection = false;
    s.paeAmbientWidth = 2.2f;
    s.paeAmbienceGainDb = 2;
    return s;
}

void rendererMatrix(int renderer, const std::string& sofa) {
    const auto input = signal();
    auto base = settings(0);
    base.width = 1;
    spatial::Engine engine(48000, renderer == 1 ? "" : sofa, renderer, 0, base);
    for (bool virtualStereo : {false, true}) {
        base.virtualStereo = virtualStereo;
        engine.reset();
        const auto dry = render(engine, input, base);
        for (int algorithm = 0; algorithm < 11; ++algorithm) {
            context = "matrix renderer=" + std::to_string(renderer) + " virtual=" +
                std::to_string(virtualStereo) + " algorithm=" + std::to_string(algorithm);
            auto s = settings(algorithm);
            s.virtualStereo = virtualStereo;
            engine.reset();
            const auto wide = render(engine, input, s);
            require(energy(wide) > .0001, "widening produced silence");
            require(difference(wide, dry) > .01, "selected algorithm did not reach output");
            engine.reset();
            close(render(engine, input, s, true), wide, "reset/fragment invariance");
            if (algorithm > 0 && !virtualStereo) {
                s.wideningAmount = 0;
                for (int count : {1, 17, 1023, 1025}) {
                    engine.reset();
                    const auto shortInput = signal(count);
                    close(render(engine, shortInput, s, true), shortInput, "zero amount must be exact dry", 2e-7f);
                }
            }
        }
    }
    std::cout << "PASS renderer " << renderer << ": eleven algorithms, direct/virtual stereo, reset/chunks, zero amount/odd EOS\n";
}

void ignoredOutsideWidth(int renderer, const std::string& sofa) {
    const auto input = signal();
    for (int mode : {0, 2}) {
        context = "ignored mode=" + std::to_string(mode) + " renderer=" + std::to_string(renderer);
        auto base = settings(0);
        base.mode = mode;
        base.virtualStereo = true;
        spatial::Engine original(48000, renderer == 1 ? "" : sofa, renderer, 0, base);
        const auto expected = render(original, input, base);
        auto changed = base;
        changed.wideningAlgorithm = 5;
        changed.wideningAmount = 1;
        changed.widenerLow = .8f;
        changed.widenerHigh = 1;
        changed.widenerCrossover = 3500;
        changed.widenerAmplitudePreserving = true;
        spatial::Engine alternate(48000, renderer == 1 ? "" : sofa, renderer, 0, changed);
        close(render(alternate, input, changed), expected, "width controls changed another mode");
        alternate.reset();
        close(render(alternate, input, changed, true), expected, "width controls changed another mode after reset");
        for (int algorithm = 1; algorithm < 11; ++algorithm) {
            changed.wideningAlgorithm = algorithm;
            alternate.reset();
            close(render(alternate, input, changed), expected, "selected algorithm leaked into another mode");
        }
    }
    std::cout << "PASS renderer " << renderer << ": stereo and 7.1 ignore widening controls\n";
}

void extraOracle(const std::string& sofa) {
    context = "extrastereo analytic oracle";
    const auto input = signal(8193);
    auto s = settings(1);
    s.wideningAmount = .5f;
    spatial::Engine engine(48000, sofa, 0, 0, s);
    auto expected = input;
    for (size_t i = 0; i < input.size(); i += 2) {
        const float mid = .5f * (input[i] + input[i + 1]);
        const float side = .5f * (input[i] - input[i + 1]);
        expected[i] = mid + 1.5f * side;
        expected[i + 1] = mid - 1.5f * side;
    }
    close(render(engine, input, s, true), expected, "50% extrastereo must equal broadband M/S x1.5", 2e-7f);
    std::cout << "PASS extrastereo known M/S x1.5 reference through whole Engine\n";
}

void liveChanges(int renderer, const std::string& sofa) {
    context = "live renderer=" + std::to_string(renderer) + " algorithm=0";
    auto s = settings(0);
    s.virtualStereo = true;
    spatial::Engine engine(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
    // A four-block loop contains a common primary and independent broadband
    // ambience in BOTH channels. The previous disjoint fixed-bin tones were
    // rank-one primaries in each STFT bin, so PAE correctly found no ambience.
    // This period exceeds its covariance history. Each steady() ends at the
    // same loop phase, making outputs directly comparable without a reset.
    constexpr int blockFrames = 1024, periodBlocks = 4;
    std::vector<float> input(blockFrames * periodBlocks * 2);
    constexpr float pi = 3.14159265358979323846f;
    uint32_t leftSeed = 0x9156ca3du, rightSeed = 0x47b12709u;
    for (int i = 0; i < blockFrames * periodBlocks; ++i) {
        leftSeed = leftSeed * 1664525u + 1013904223u;
        rightSeed ^= rightSeed << 13;
        rightSeed ^= rightSeed >> 17;
        rightSeed ^= rightSeed << 5;
        const float primary = .009f * std::sin(2 * pi * 71 * i / (blockFrames * periodBlocks));
        input[i * 2] = primary + .013f * (float(leftSeed >> 8) / 8388608.f - 1);
        input[i * 2 + 1] = primary + .013f * (float(rightSeed >> 8) / 8388608.f - 1);
    }
    auto steady = [&](spatial::Engine& target, const spatial::Settings& snapshot) {
        std::vector<float> output;
        for (int i = 0; i < 28; ++i) {
            const auto* block = input.data() + (i % periodBlocks) * blockFrames * 2;
            output = target.process(block, blockFrames, 2, snapshot);
            valid(output, blockFrames * 2);
            if (i > 2) require(energy(output) > .00001, "live update inserted silence");
        }
        return output;
    };
    auto drySettings = s;
    drySettings.width = 1;
    spatial::Engine dryEngine(48000, renderer == 1 ? "" : sofa, renderer, 0, drySettings);
    const auto dryReference = steady(dryEngine, drySettings);
    auto previous = steady(engine, s);
    for (int algorithm = 1; algorithm <= 10; ++algorithm) {
        context = "live renderer=" + std::to_string(renderer) + " algorithm=" + std::to_string(algorithm);
        s.wideningAlgorithm = algorithm;
        s.wideningAmount = .65f;
        const auto widened = steady(engine, s);
        const double selectionDelta = difference(widened, previous);
        require(selectionDelta > .001, "live algorithm choice had no effect; difference=" + std::to_string(selectionDelta));
        s.wideningAmount = 0;
        const auto bypass = steady(engine, s);
        const double amountDelta = difference(widened, bypass);
        require(amountDelta > .001, "live amount control had no effect; difference=" + std::to_string(amountDelta));
        // A time-varying effect such as MicroPitch could differ across time even
        // if its amount control were broken. Zero must also converge to a second
        // engine's unprocessed stereo rendered through the same headphone path.
        close(bypass, dryReference, "live zero amount must converge to rendered dry stereo", 4e-6f);
        s.wideningAmount = .65f;
        previous = steady(engine, s);
    }
    std::cout << "PASS renderer " << renderer << ": uninterrupted live algorithm/amount changes\n";
}

void paeIdentity(int renderer, const std::string& sofa) {
    context = "PAE identity renderer=" + std::to_string(renderer);
    auto s = settings(7);
    s.wideningAmount = 1;
    s.paeAmbientWidth = 1;
    s.paeAmbienceGainDb = 0;
    const auto input = signal(8193);
    spatial::Engine engine(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
    close(render(engine, input, s, true), input, "neutral PAE must reconstruct direct stereo", 2e-7f);
    for (int frames : {1, 17, 1023, 1025}) {
        engine.reset();
        const auto shortInput = signal(frames);
        close(render(engine, shortInput, s, true), shortInput, "neutral PAE short EOS", 2e-7f);
    }
    auto mono = input;
    for (size_t i = 0; i < mono.size(); i += 2) mono[i + 1] = mono[i];
    s.paeAmbientWidth = 3;
    s.paeAmbienceGainDb = 6;
    engine.reset();
    close(render(engine, mono, s, true), mono, "PAE must not create ambience or width from coherent mono", 4e-6f);
    std::cout << "PASS renderer " << renderer << ": PAE neutral identity, odd EOS, coherent mono preserved\n";
}

void paeDiagnosticOracle(const std::string& sofa) {
    context = "PAE nonneutral standalone diagnostic oracle";
    constexpr int rate = 48000, frames = 8193;
    std::vector<float> input(frames*2);
    uint32_t seed = 0x191072abu;
    for (int frame = 0; frame < frames; ++frame) {
        const float primary = .004f*std::cos(.173f*frame);
        for (int channel = 0; channel < 2; ++channel) {
            seed = seed*1664525u+1013904223u;
            input[frame*2+channel] = primary+.012f*(float(seed>>8)/8388608.f-1);
        }
    }
    spatial::PaeUpmixer standalone(rate);
    const int latency = standalone.latencyFrames();
    require(latency==1024,"Standalone PAE latency contract changed");
    auto padded = input;
    padded.resize((frames+latency)*2);
    std::vector<float> wave71((frames+latency)*8), diagnostics((frames+latency)*4);
    standalone.process(padded.data(),wave71.data(),frames+latency,diagnostics.data());
    auto s = settings(7);
    s.virtualStereo = false;
    s.wideningAmount = 1;
    s.paeAmbientWidth = 2.4f;
    s.paeAmbienceGainDb = 3.5f;
    const double gain = std::pow(10.0,double(s.paeAmbienceGainDb)/20);
    std::vector<float> expected(input.size());
    double ambientEnergy = 0;
    for (int frame = 0; frame < frames; ++frame) {
        // The standalone API owns only its 1024-frame analysis delay. The
        // Engine's additional block transport delay is trimmed by render().
        // Looking up the compensated diagnostics here independently catches
        // an Engine that accidentally combines A[n-d] with dry[n] or vice versa.
        const auto* separated = diagnostics.data()+(frame+latency)*4;
        const double ambientL = separated[2], ambientR = separated[3];
        require(std::abs(double(separated[0])+ambientL-input[frame*2])<2e-7 &&
                std::abs(double(separated[1])+ambientR-input[frame*2+1])<2e-7,
                "Standalone diagnostics do not reconstruct the compensated input");
        const double mid = .5*(ambientL+ambientR);
        const double side = .5*(ambientL-ambientR)*s.paeAmbientWidth;
        expected[frame*2] = float(input[frame*2]-ambientL+gain*(mid+side));
        expected[frame*2+1] = float(input[frame*2+1]-ambientR+gain*(mid-side));
        ambientEnergy += ambientL*ambientL+ambientR*ambientR;
    }
    require(ambientEnergy>.001 && difference(expected,input)>.1,
            "Oracle fixture contains insufficient ambience for a nonneutral comparison");
    for (int upmixer = 0; upmixer < 3; ++upmixer) {
        context = "PAE diagnostic oracle renderer=0 upmixer="+std::to_string(upmixer);
        spatial::Engine engine(rate,sofa,0,upmixer,s);
        close(render(engine,input,s,true),expected,
              "Nonneutral PAE must match standalone delay-compensated P/A diagnostics",2e-7f);
    }
    std::cout << "PASS PAE nonneutral standalone diagnostics: independent delay compensation and ambience formula, all three upmixers, odd duration\n";
}

void paeLfeBypass(int renderer, const std::string& sofa) {
    context = "PAE isolated LFE renderer=" + std::to_string(renderer);
    constexpr int frames = 4097;
    std::vector<float> input(frames * 8), expected(frames * 2);
    auto s = settings(7);
    s.wideningAmount = 1;
    s.paeAmbientWidth = 3;
    s.paeAmbienceGainDb = 6;
    s.lfeGain = .45f;
    for (int i = 0; i < frames; ++i) {
        input[i*8+3] = .024f * std::sin(.067f * i);
        expected[i*2] = expected[i*2+1] = input[i*8+3] * s.lfeGain * .7071067811865475f * .5f;
    }
    spatial::Engine engine(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
    close(render(engine, input, s, true, 8), expected, "PAE ambience must not contain LFE", 2e-7f);
    s.paeAmbientWidth = 0;
    s.paeAmbienceGainDb = -12;
    engine.reset();
    close(render(engine, input, s, true, 8), expected, "PAE controls must leave isolated LFE fold unchanged", 2e-7f);
    std::cout << "PASS renderer " << renderer << ": LFE excluded from PAE ambience but retained in direct downmix\n";
}

void roomMatrix(int renderer, const std::string& sofa) {
    const auto input = signal(8193);
    for (int mode = 0; mode < 3; ++mode) {
        context = "room renderer=" + std::to_string(renderer) + " mode=" + std::to_string(mode);
        auto s = settings(8);
        s.mode = mode;
        s.roomMix = .5f;
        spatial::Engine engine(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
        for (bool virtualStereo : {false, true}) {
            if (mode == 2 && virtualStereo) continue;
            s.virtualStereo = virtualStereo;
            s.roomEnabled = false;
            engine.reset();
            const auto dry = render(engine, input, s);
            s.roomEnabled = true;
            s.roomMix = 0;
            engine.reset();
            close(render(engine, input, s, true), dry, "room zero mix must preserve dry path");
            s.roomMix = .5f;
            engine.reset();
            const auto withRoom = render(engine, input, s);
            require(energy(withRoom) > .0001, "enabled room muted the direct path");
            require(difference(withRoom, dry) > .001, "room did not reach output");
            engine.reset();
            close(render(engine, input, s, true), withRoom, "room reset/chunk invariance");
            s.roomEnabled = false;
            engine.reset();
            close(render(engine, input, s), dry, "room off after reset must preserve dry path");
        }
    }
    std::cout << "PASS renderer " << renderer << ": room all modes, virtual on/off, zero mix/off identity, reset/chunks\n";
}

void liveRoom(int renderer, const std::string& sofa) {
    constexpr float pi = 3.14159265358979323846f;
    std::vector<float> input(2048);
    for (int i = 0; i < 1024; ++i) {
        input[2*i] = .012f * std::sin(2*pi*23*i/1024) + .007f * std::cos(2*pi*81*i/1024);
        input[2*i+1] = .011f * std::cos(2*pi*31*i/1024) + .005f * std::sin(2*pi*103*i/1024);
    }
    for (int mode = 0; mode < 3; ++mode) {
        context = "live room renderer=" + std::to_string(renderer) + " mode=" + std::to_string(mode);
        auto s = settings(1);
        s.mode = mode;
        // The room must remain audible without virtual direct speakers too.
        s.virtualStereo = mode == 1;
        s.roomMix = .65f;
        spatial::Engine engine(48000, renderer == 1 ? "" : sofa, renderer, 0, s);
        const auto steady = [&] {
            std::vector<float> output;
            for (int block = 0; block < 56; ++block) {
                output = engine.process(input.data(), 1024, 2, s);
                valid(output, input.size());
                if (block > 2) require(energy(output) > .00001, "room live change inserted silence");
            }
            return output;
        };
        const auto dry = steady();
        s.roomEnabled = true;
        const auto wet = steady();
        require(difference(dry, wet) > .001, "live room enable did not affect sound");
        s.roomSize = 10;
        s.roomDamping = .85f;
        s.roomDecay = .8f;
        const auto changed = steady();
        require(difference(wet, changed) > .001, "live room geometry/damping did not affect sound");
        s.roomMix = 0;
        close(steady(), dry, "live zero room mix must settle to dry", 5e-6f);
        s.roomMix = .65f;
        require(difference(steady(), dry) > .001, "live room amount did not restore reflections");
        s.roomEnabled = false;
        close(steady(), dry, "live room off must settle to dry", 5e-6f);
    }
    std::cout << "PASS renderer " << renderer << ": live room enable/amount/size/damping/decay in every mode\n";
}
}

int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("usage: widening_engine_smoke PROFILE.sofa");
        extraOracle(argv[1]);
        paeDiagnosticOracle(argv[1]);
        for (int renderer = 0; renderer < 3; ++renderer) {
            rendererMatrix(renderer, argv[1]);
            ignoredOutsideWidth(renderer, argv[1]);
            liveChanges(renderer, argv[1]);
            paeIdentity(renderer, argv[1]);
            paeLfeBypass(renderer, argv[1]);
            roomMatrix(renderer, argv[1]);
            liveRoom(renderer, argv[1]);
        }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
