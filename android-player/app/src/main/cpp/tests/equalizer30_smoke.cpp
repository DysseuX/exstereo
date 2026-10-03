/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "equalizer30.h"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
constexpr double pi = 3.14159265358979323846;
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
std::vector<float> tone(int rate, double frequency, double amplitude = .1) {
    std::vector<float> samples(rate * 2);
    for (int frame = 0; frame < rate; ++frame)
        samples[frame * 2] = amplitude * std::sin(2 * pi * frequency * frame / rate);
    return samples;
}
double rms(const std::vector<float>& samples) {
    double sum = 0;
    for (size_t index = samples.size() / 2; index < samples.size(); index += 2)
        sum += double(samples[index]) * samples[index];
    return std::sqrt(sum / (samples.size()/4));
}
void frequencyResponse(int rate) {
    for (size_t band = 0; band < spatial::Equalizer30::kBands; ++band) {
        const auto frequency = spatial::Equalizer30::frequencies[band];
        if (frequency >= rate * .49) continue;
        const auto original = tone(rate, frequency);
        for (float gain : {-6.f, 6.f}) {
            auto samples = original;
            spatial::Equalizer30 eq(rate);
            std::array<float, 30> gains{}; gains[band] = gain;
            eq.setGains(gains);
            eq.process(samples.data(), rate);
            const double measured = 20 * std::log10(rms(samples)/rms(original));
            require(std::abs(measured - (gain - eq.headroomDb())) < .025,
                "A 30-band slider does not produce its expected center-frequency gain");
            for (size_t index = 1; index < samples.size(); index += 2)
                require(samples[index] == 0, "EQ introduced stereo crosstalk");
        }
    }
}
void streaming() {
    const int rate = 48000;
    auto input = tone(rate, 1111);
    for (size_t index = 1; index < input.size(); index += 2) input[index] = input[index-1] * -.6f;
    auto flat = input;
    spatial::Equalizer30 plain(rate);
    plain.process(flat.data(), rate);
    require(flat == input, "Flat must be bit-identical");
    std::array<float, 30> gains{};
    for (size_t index = 0; index < 30; ++index) gains[index] = index % 2 ? 12 : -12;
    spatial::Equalizer30 whole(rate), pieces(rate);
    whole.setGains(gains); pieces.setGains(gains);
    auto expected = input, result = input;
    whole.process(expected.data(), rate);
    size_t position = 0;
    while (position < static_cast<size_t>(rate)) {
        const size_t count = std::min(size_t(rate) - position, position % 503 + 1);
        pieces.process(result.data() + position * 2, count);
        position += count;
    }
    require(result == expected, "Chunk boundaries changed the EQ");
    pieces.reset(); result = input; pieces.process(result.data(), rate);
    require(result == expected, "Reset leaked filter history");
    pieces.setGains({});
    result = input; pieces.process(result.data(), rate);
    result = input; pieces.process(result.data(), rate);
    require(result == input, "Reset to Flat did not reach exact bypass");
    gains.fill(12); pieces.setGains(gains);
    result = tone(rate, 1000, .99); pieces.process(result.data(), rate);
    require(pieces.headroomDb() > 12, "Automatic headroom omitted band overlap");
    for (float sample : result) require(std::isfinite(sample) && std::abs(sample) <= 1,
        "Boosted EQ output clipped beyond PCM limits");
    gains[0] = std::numeric_limits<float>::quiet_NaN();
    bool rejected = false;
    try { pieces.setGains(gains); } catch (const std::invalid_argument&) { rejected = true; }
    require(rejected, "NaN gains accepted");
    spatial::Equalizer30 lowRate(8000);
    gains = {}; gains[29] = 12; lowRate.setGains(gains);
    result = tone(8000, 1000); expected = result; lowRate.process(result.data(), 8000);
    require(result == expected, "A band above Nyquist changed audible samples");
}
void liveChanges() {
    const int rate = 48000;
    auto samples = tone(rate, 1000);
    spatial::Equalizer30 eq(rate);
    eq.process(samples.data(), rate/2);
    std::array<float, 30> gains{}; gains[16] = 12;
    eq.setGains(gains);
    eq.process(samples.data() + rate, rate/2);
    double step = 0;
    for (size_t frame = rate/2; frame < rate/2 + 4000; ++frame)
        step = std::max(step, std::abs(double(samples[frame*2]) - samples[(frame-1)*2]));
    require(step < .04, "Changing EQ caused a large discontinuity");
}
}
int main() {
    try {
        frequencyResponse(44100);
        frequencyResponse(48000);
        streaming(); liveChanges();
        std::cout << "30-band EQ: all centers, gain, isolation, Flat, chunks, reset, Nyquist, headroom and live changes passed\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n'; return 1;
    }
}
