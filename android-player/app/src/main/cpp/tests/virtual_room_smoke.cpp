/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "virtual_room.h"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
void require(bool test, const char* why) { if (!test) throw std::runtime_error(why); }
std::vector<float> render(spatial::VirtualRoom& room, const std::vector<float>& input,
                          const spatial::RoomSettings& p, bool chunks = false) {
    const int count = int(input.size()/7);
    std::vector<float> output(count*2);
    const int sizes[] = {1,17,257,1023,73};
    int offset = 0, index = 0;
    while (offset < count) {
        const int n = std::min(count-offset, chunks ? sizes[index++ % 5] : count);
        room.process(input.data()+offset*7, output.data()+offset*2, n, p);
        offset += n;
    }
    for (float x : output) require(std::isfinite(x) && std::abs(x) < 16, "invalid/unstable room PCM");
    return output;
}
double energy(const std::vector<float>& samples, int start = 0) {
    double sum = 0;
    for (size_t i = size_t(start)*2; i < samples.size(); ++i) sum += double(samples[i])*samples[i];
    return sum;
}
void compare(const std::vector<float>& a, const std::vector<float>& b) {
    require(a.size()==b.size(), "duration");
    for (size_t i = 0; i < a.size(); ++i) require(std::abs(a[i]-b[i])<1e-7, "room chunk/reset invariance");
}
void rateTest(int rate) {
    spatial::VirtualRoom room(rate);
    spatial::RoomSettings p;
    p.enabled = true; p.mix = 1; p.azimuths.fill(0); p.elevations.fill(0); p.distances.fill(1);
    std::vector<float> impulse(rate*7);
    impulse[2] = .3f;
    const auto out = render(room, impulse, p);
    require(energy(out)>1e-5, "no room reflections");
    // Six image sources in a 5 x 6.25 x 3 m room. For a frontal source
    // one metre away, floor/ceiling images at y=+/-3 arrive first. Direct
    // time of flight is subtracted, because the direct renderer omits it.
    const int first = int(std::floor((std::sqrt(10.)-1)*rate/343.));
    for (int i = 0; i < first; ++i) require(out[i*2]==0 && out[i*2+1]==0, "reflection arrived before image-source path");
    require(std::abs(out[first*2])+std::abs(out[first*2+1])>1e-7, "incorrect first reflection timing");
    room.reset(); compare(out, render(room, impulse, p, true));
    room.reset(); p.enabled = false;
    require(energy(render(room, impulse, p))==0, "disabled room nonzero");
    room.reset(); p.enabled = true; p.mix = 0;
    require(energy(render(room, impulse, p))==0, "zero room mix nonzero");
    p.mix = 1; p.decay = .1f; room.reset();
    const auto shortRoom = render(room, impulse, p);
    p.decay = 1.2f; room.reset();
    const auto longRoom = render(room, impulse, p);
    require(energy(longRoom,rate/5)>10*energy(shortRoom,rate/5), "decay control ineffective");
    p.size = 10; room.reset();
    const auto large = render(room, impulse, p);
    require(energy(large)!=energy(longRoom), "size control ineffective");
    p.size = 5; p.damping = 1; room.reset();
    const auto damp = render(room, impulse, p);
    require(energy(damp)<energy(longRoom), "absorption should attenuate reflections");
    // Symmetric left/right scene mirror must swap the binaural early response.
    p.decay = .35f; p.damping = .5f; p.azimuths.fill(-60); room.reset();
    const auto left = render(room, impulse, p);
    p.azimuths.fill(60); room.reset();
    const auto right = render(room, impulse, p);
    for (int i = 0; i < int(.025*rate); ++i) {
        require(std::abs(left[i*2]-right[i*2+1])<2e-7, "mirrored reflection ITD/ILD");
        require(std::abs(left[i*2+1]-right[i*2])<2e-7, "mirrored reflection ITD/ILD");
    }
    p.size = std::numeric_limits<float>::quiet_NaN();
    p.decay = std::numeric_limits<float>::infinity();
    p.yaw = std::numeric_limits<float>::infinity();
    p.distances.fill(std::numeric_limits<float>::quiet_NaN());
    room.reset(); render(room, impulse, p);
    room.process(nullptr,nullptr,0,p);
    bool invalid = false;
    try { room.process(nullptr,nullptr,1,p); } catch (const std::invalid_argument&) { invalid = true; }
    require(invalid,"null buffers accepted");
    std::cout << "PASS room " << rate << ": analytic reflection timing, mirror, decay/damping/size, reset/chunks, zero/off, finite bounds\n";
}

void fadeAndReactivate() {
    constexpr int rate = 48000, frames = rate / 8;
    spatial::RoomSettings enabled;
    enabled.enabled = true; enabled.mix = 1;
    auto disabled = enabled; disabled.enabled = false;
    std::vector<float> input(frames * 7);
    for (int i = 0; i < frames; ++i) {
        input[i*7] = float(.1 * std::sin(i * .131));
        input[i*7+1] = float(.1 * std::sin(i * .091));
    }
    spatial::VirtualRoom baseline(rate), toggled(rate);
    std::vector<float> oneInput(7), oneOutput(2);
    toggled.process(oneInput.data(),oneOutput.data(),1,disabled);
    require(oneOutput[0]==0 && oneOutput[1]==0,"initially disabled room output");
    const auto raw = render(baseline,input,enabled);
    const auto fading = render(toggled,input,enabled,true);
    for (int frame = 0; frame < frames; ++frame) {
        const double envelope = 1-std::exp(-(frame+1)/(.05*rate));
        for (int ear = 0; ear < 2; ++ear)
            require(std::abs(fading[frame*2+ear]-raw[frame*2+ear]*envelope)<2e-7,
                    "First off-to-on change did not fade over 50 ms");
    }
    // Both histories are otherwise identical: room mix must not change the FDN
    // excitation. Turning it off follows the same scalar envelope sample-wise.
    const auto continuing = render(baseline,input,enabled);
    const auto stopping = render(toggled,input,disabled,true);
    const double previousMix = 1-std::exp(-frames/(.05*rate));
    for (int frame = 0; frame < frames; ++frame) {
        const double envelope = previousMix*std::exp(-(frame+1)/(.05*rate));
        for (int ear = 0; ear < 2; ++ear)
            require(std::abs(stopping[frame*2+ear]-continuing[frame*2+ear]*envelope)<2e-7,
                    "Room off transition jumps or modifies feedback excitation");
    }
    std::vector<float> silence(rate*2*7);
    const auto off = render(toggled,silence,disabled,true);
    for (std::size_t i = off.size()-200; i < off.size(); ++i)
        require(off[i]==0,"Room did not reach an exact disabled state");
    baseline.reset();
    const auto fresh = render(baseline,input,enabled);
    const auto reactivated = render(toggled,input,enabled,true);
    for (int frame = 0; frame < frames; ++frame) {
        const double envelope = 1-std::exp(-(frame+1)/(.05*rate));
        for (int ear = 0; ear < 2; ++ear)
            require(std::abs(reactivated[frame*2+ear]-fresh[frame*2+ear]*envelope)<2e-7,
                    "Reactivated room retained stale echoes or skipped its fade");
    }
    std::cout << "PASS room live off/on: sample-wise fade oracle, exact off, clean reactivation\n";
}

void extremeGeometryAndDecay(int rate) {
    spatial::RoomSettings p;
    p.enabled = true; p.mix = 1; p.size = 12; p.decay = 1.5f; p.damping = 0;
    p.yaw = 180; p.pitch = -60;
    for (int source = 0; source < 7; ++source) {
        p.azimuths[source] = source%2 ? -3600.f : 3600.f;
        p.elevations[source] = source%2 ? -90.f : 90.f;
        p.distances[source] = 5;
    }
    std::vector<float> input(rate*2*7);
    for (int source = 0; source < 7; ++source) input[source] = .1f;
    spatial::VirtualRoom room(rate);
    const auto output = render(room,input,p,true);
    require(energy(output)>1e-6,"Extreme geometry has no reflection");
    require(energy(output,int(rate*1.75))<energy(output)*1e-5,
            "Maximum-decay FDN does not dissipate energy");
    room.reset(); compare(output,render(room,input,p));
    // Source zero remains inside the small room; hidden saved 7.1 positions
    // include a source whose image would otherwise be exactly at the listener.
    p = spatial::RoomSettings{};
    p.enabled = true; p.mix = 1; p.size = 2; p.activeSources = 2;
    p.azimuths.fill(0); p.elevations.fill(0); p.distances.fill(.5f);
    std::fill(input.begin(),input.end(),0); input[0] = .1f; input[1] = .05f;
    room.reset(); const auto stereo = render(room,input,p,true);
    for (int source = 2; source < 7; ++source) p.distances[source] = 2.5f;
    room.reset(); compare(stereo,render(room,input,p,true));
    for (int source = 2; source < 7; ++source) {
        p.distances[source] = 5; p.elevations[source] = 90;
    }
    room.reset(); compare(stereo,render(room,input,p,true));
    p.activeSources = 1; input[1] = 0;
    room.reset(); const auto one = render(room,input,p,true);
    p.activeSources = std::numeric_limits<int>::min();
    room.reset(); compare(one,render(room,input,p,true));
    p.activeSources = 7;
    room.reset(); const auto seven = render(room,input,p,true);
    p.activeSources = std::numeric_limits<int>::max();
    room.reset(); compare(seven,render(room,input,p,true));
    bool failed = false;
    float in[7]{}, out[2]{};
    try { room.process(in,out,-1,p); } catch (const std::invalid_argument&) { failed = true; }
    require(failed,"Room accepted negative frame count");
    failed = false;
    try { room.process(in,out,1048577,p); } catch (const std::invalid_argument&) { failed = true; }
    require(failed,"Room accepted unsafe oversized frame count");
    std::cout << "PASS room " << rate << ": extreme geometry/ring wraps, maximum decay stability, inactive sources, source count bounds\n";
}
}
int main() {
    try {
        for (int rate : {8000,44100,48000,96000}) rateTest(rate);
        fadeAndReactivate();
        for (int rate : {8000,96000}) extremeGeometryAndDecay(rate);
        for (int rate : {0,7999,96001,std::numeric_limits<int>::max()}) {
            bool failed = false;
            try { spatial::VirtualRoom invalid(rate); } catch (const std::invalid_argument&) { failed = true; }
            require(failed,"Room accepted unsupported sample rate");
        }
        return 0;
    }
    catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
