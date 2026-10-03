/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "virtual_room.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <vector>

namespace spatial {
namespace {
constexpr double pi = 3.14159265358979323846;
double bound(double x, double lo, double hi, double fallback) {
    return std::isfinite(x) ? std::max(lo, std::min(hi, x)) : fallback;
}
double approach(double x, double target, double step) {
    const double result = x + step * (target - x);
    return std::abs(result - target) < 1e-9 ? target : result;
}
struct Point { double x, y, z; };
struct Ray {
    std::array<double, 2> delay{}, gain{}, coefficient{}, state{};
};
int checkedRate(int rate) {
    if (rate < 8000 || rate > 96000) throw std::invalid_argument("Room rate must be 8000..96000");
    return rate;
}
}

class VirtualRoom::Impl {
public:
    explicit Impl(int sampleRate) : rate(checkedRate(sampleRate)),
        length(int(rate * .25) + 4), step(1 - std::exp(-1 / (.05 * rate))) {
        for (auto& line : history) line.resize(length);
        const double times[] = {.0297, .0371, .0411, .0437};
        for (int i = 0; i < 4; ++i) late[i].resize(std::max(1, int(std::lround(times[i] * rate))));
    }
    void clearHistories() {
        for (auto& line : history) std::fill(line.begin(), line.end(), 0);
        for (auto& line : late) std::fill(line.begin(), line.end(), 0);
        for (auto& ray : rays) ray.state = {};
        latePosition = {}; lateState = {}; position = 0;
    }
    void reset() { clearHistories(); mix = 0; active = initialized = false; }

    std::array<Ray, 42> geometry(const RoomSettings& p) const {
        const double size = bound(p.size, 2, 12, 5);
        const double damping = bound(p.damping, 0, 1, .5);
        const double yaw = bound(p.yaw, -180, 180, 0) * pi / 180;
        const double pitch = bound(p.pitch, -60, 60, 0) * pi / 180;
        std::array<Point, 7> sources{};
        const int activeSources = std::max(1, std::min(7, p.activeSources));
        double half[3] = {size / 2, 1.5, size * .625};
        for (int s = 0; s < 7; ++s) {
            const double az = bound(p.azimuths[s], -3600, 3600, 0) * pi / 180;
            const double el = bound(p.elevations[s], -90, 90, 0) * pi / 180;
            const double distance = bound(p.distances[s], .5, 5, 1);
            sources[s] = {distance * std::sin(az) * std::cos(el), distance * std::sin(el),
                          -distance * std::cos(az) * std::cos(el)};
            if (s < activeSources) {
                half[0] = std::max(half[0], std::abs(sources[s].x) + .4);
                half[1] = std::max(half[1], std::abs(sources[s].y) + .4);
                half[2] = std::max(half[2], std::abs(sources[s].z) + .4);
            }
        }
        std::array<Ray, 42> target{};
        for (int s = 0; s < 7; ++s) for (int wall = 0; wall < 6; ++wall) {
            if (s >= activeSources) {
                // Inactive sources may be outside the room. Their mirrored
                // position can coincide with the listener: never divide by it.
                target[s * 6 + wall].delay.fill(1);
                target[s * 6 + wall].coefficient.fill(1);
                continue;
            }
            Point image = sources[s];
            double* coordinate = wall / 2 == 0 ? &image.x : wall / 2 == 1 ? &image.y : &image.z;
            *coordinate = (wall % 2 ? 2 : -2) * half[wall / 2] - *coordinate;
            const double distance = std::sqrt(image.x*image.x + image.y*image.y + image.z*image.z);
            const double directDistance = std::sqrt(sources[s].x*sources[s].x + sources[s].y*sources[s].y + sources[s].z*sources[s].z);
            // Listener-relative direction: inverse yaw then inverse pitch.
            const double x = std::cos(yaw)*image.x + std::sin(yaw)*image.z;
            const double z = -std::sin(yaw)*image.x + std::cos(yaw)*image.z;
            const double y = std::cos(pitch)*image.y + std::sin(pitch)*z;
            const double zz = -std::sin(pitch)*image.y + std::cos(pitch)*z;
            const double pan = bound(x / std::sqrt(x*x + y*y + zz*zz), -1, 1, 0);
            const double itd = .00065 * pan * rate;
            const double echo = (distance - directDistance) * rate / 343.;
            auto& ray = target[s * 6 + wall];
            for (int ear = 0; ear < 2; ++ear) {
                const double far = std::max(0.0, ear == 0 ? pan : -pan);
                ray.delay[ear] = bound(echo + (ear == 0 ? std::max(0.0, itd) : std::max(0.0, -itd)), 1, length - 2, 1);
                ray.gain[ear] = .35 * (1 - .45*damping) / distance * std::sqrt(.5 * (1 + (ear == 0 ? -.65 : .65)*pan));
                const double cutoff = std::min(.45 * rate, 14000. / (1 + .08*distance) * (1 - .75*damping) * (1 - .75*far));
                ray.coefficient[ear] = 1 - std::exp(-2*pi*cutoff/rate);
            }
        }
        return target;
    }

    void process(const float* input, float* output, int frames, const RoomSettings& p) {
        if (frames < 0 || frames > 1048576 || (frames && (!input || !output)))
            throw std::invalid_argument("Invalid room buffers/frame count");
        if (!frames) return;
        const double wantedMix = p.enabled ? bound(p.mix, 0, 1, .15) : 0;
        if (!active && wantedMix == 0) {
            // A later live enable must fade from silence, just like re-enabling.
            initialized = true;
            std::fill_n(output, frames * 2, 0);
            return;
        }
        const auto targets = geometry(p);
        const double decay = bound(p.decay, .1, 1.5, .35);
        const double damping = bound(p.damping, 0, 1, .5);
        std::array<double, 4> gains{};
        for (int i = 0; i < 4; ++i) gains[i] = std::pow(.001, double(late[i].size()) / (rate * decay));
        const double damp = 1 - std::exp(-2*pi*std::min(.45*rate, 10000.*(1-.9*damping))/rate);
        if (!active) {
            clearHistories(); rays = targets; feedback = gains; lateCoefficient = damp;
            active = true;
        }
        if (!initialized) { mix = wantedMix; initialized = true; }
        for (int f = 0; f < frames; ++f) {
            mix = approach(mix, wantedMix, step);
            double left = 0, right = 0;
            for (int s = 0; s < 7; ++s) {
                history[s][position] = bound(input[f*7+s], -16, 16, 0);
                for (int wall = 0; wall < 6; ++wall) {
                    auto& ray = rays[s*6+wall];
                    const auto& target = targets[s*6+wall];
                    for (int ear = 0; ear < 2; ++ear) {
                        ray.delay[ear] = approach(ray.delay[ear], target.delay[ear], step);
                        ray.gain[ear] = approach(ray.gain[ear], target.gain[ear], step);
                        ray.coefficient[ear] = approach(ray.coefficient[ear], target.coefficient[ear], step);
                        const int delay = int(ray.delay[ear]);
                        const double fraction = ray.delay[ear] - delay;
                        int a = position - delay;
                        if (a < 0) a += length;
                        const int b = a ? a-1 : length-1;
                        const double sample = history[s][a]*(1-fraction) + history[s][b]*fraction;
                        ray.state[ear] += ray.coefficient[ear] * (sample - ray.state[ear]);
                        if (std::abs(ray.state[ear]) < 1e-30) ray.state[ear] = 0;
                        (ear == 0 ? left : right) += ray.gain[ear] * ray.state[ear];
                    }
                }
            }
            std::array<double, 4> delayed{};
            lateCoefficient = approach(lateCoefficient, damp, step);
            for (int i = 0; i < 4; ++i) {
                feedback[i] = approach(feedback[i], gains[i], step);
                lateState[i] += lateCoefficient * (late[i][latePosition[i]] - lateState[i]);
                if (std::abs(lateState[i]) < 1e-30) lateState[i] = 0;
                delayed[i] = lateState[i] * feedback[i];
            }
            // Orthogonal Hadamard feedback; every line's gain remains < 1.
            const double rotated[] = {
                .5*(delayed[0]+delayed[1]+delayed[2]+delayed[3]),
                .5*(delayed[0]-delayed[1]+delayed[2]-delayed[3]),
                .5*(delayed[0]+delayed[1]-delayed[2]-delayed[3]),
                .5*(delayed[0]-delayed[1]-delayed[2]+delayed[3])};
            for (int i = 0; i < 4; ++i) {
                late[i][latePosition[i]] = float(rotated[i] + .25*(i%2 ? right : left));
                if (++latePosition[i] == int(late[i].size())) latePosition[i] = 0;
            }
            output[2*f] = float(mix*(left + .25*(lateState[0]+lateState[2])));
            output[2*f+1] = float(mix*(right + .25*(lateState[1]-lateState[3])));
            if (++position == length) position = 0;
        }
        if (wantedMix == 0 && mix < 1e-9) { active = false; mix = 0; }
    }
private:
    int rate, length, position = 0;
    double step, mix = 0, lateCoefficient = 0;
    bool active = false, initialized = false;
    std::array<std::vector<float>, 7> history;
    std::array<Ray, 42> rays{};
    std::array<std::vector<float>, 4> late;
    std::array<int, 4> latePosition{};
    std::array<double, 4> lateState{}, feedback{};
};
VirtualRoom::VirtualRoom(int rate) : impl_(new Impl(rate)) {}
VirtualRoom::~VirtualRoom() = default;
void VirtualRoom::process(const float* in, float* out, int frames, const RoomSettings& p) { impl_->process(in, out, frames, p); }
void VirtualRoom::reset() { impl_->reset(); }
}
