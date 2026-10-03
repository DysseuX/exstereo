/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "pae_upmixer.h"
#include "FreeSurround/KissFFTR.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <cstring>
#include <stdexcept>
#include <vector>

namespace spatial {
namespace {
constexpr int kSize = 1024;
constexpr int kHop = 256;
constexpr int kBins = kSize / 2 + 1;
constexpr int kCovarianceFrames = 5;
constexpr int kFilterFrames = 3;
constexpr int kComponents = 5; // primary L/R, common center, ambient L/R
constexpr double kPi = 3.14159265358979323846;
constexpr double kSqrtTwo = 1.41421356237309504880;
constexpr double kAmbientFront = 0.5;
constexpr double kAmbientSurround = 0.61237243569579452455; // sqrt((1-.5^2)/2)
constexpr double kLfeGain = 0.31622776601683793320; // -10 dB, no LFE playback boost
using Complex = std::complex<double>;

struct Covariance {
    double left = 0, right = 0;
    Complex cross{}; // E[L * conjugate(R)]
};

float sample(float x) {
    return std::isfinite(x) ? std::max(-16.0f, std::min(16.0f, x)) : 0.0f;
}

// H(z) = (z^-d-g)/(1-g*z^-d). One state array stores x[n-d]+g*y[n-d].
// Its magnitude is one; differing delays/signs spread the extracted ambience
// without applying a fixed L-R subtraction or changing the primary channels.
class AllPass {
public:
    AllPass(int delay, double coefficient) : state_(delay, 0), coefficient_(coefficient) {}
    double process(double x) {
        const double y = state_[index_] - coefficient_ * x;
        state_[index_] = x + coefficient_ * y;
        if (++index_ == state_.size()) index_ = 0;
        return y;
    }
    void reset() { std::fill(state_.begin(), state_.end(), 0); index_ = 0; }
private:
    std::vector<double> state_;
    double coefficient_;
    size_t index_ = 0;
};

class Diffuser {
public:
    Diffuser(int rate, int d1, int d2, double g1, double g2)
        : first_(std::max(1, int(std::lround(d1 * rate / 48000.0))), g1),
          second_(std::max(1, int(std::lround(d2 * rate / 48000.0))), g2) {}
    double process(double x) { return second_.process(first_.process(x)); }
    void reset() { first_.reset(); second_.reset(); }
private:
    AllPass first_, second_;
};

class LowPass {
public:
    LowPass(int rate, double q) {
        const double omega = 2 * kPi * 120 / rate;
        const double c = std::cos(omega), alpha = std::sin(omega) / (2 * q);
        const double a0 = 1 + alpha;
        b0_ = (1 - c) / (2 * a0);
        b1_ = (1 - c) / a0;
        b2_ = b0_;
        a1_ = -2 * c / a0;
        a2_ = (1 - alpha) / a0;
    }
    double process(double x) {
        const double y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return y;
    }
    void reset() { z1_ = z2_ = 0; }
private:
    double b0_, b1_, b2_, a1_, a2_, z1_ = 0, z2_ = 0;
};

int checkedRate(int rate) {
    if (rate < 8000 || rate > 96000)
        throw std::invalid_argument("PAE sample rate must be 8000..96000 Hz");
    return rate;
}
} // namespace

class PaeUpmixer::Impl {
public:
    explicit Impl(int rate)
        : sampleRate(checkedRate(rate)),
          diffusers{{Diffuser(rate, 149, 373, .61, -.47),
                     Diffuser(rate, 211, 557, -.59, .43),
                     Diffuser(rate, 293, 701, .53, .39),
                     Diffuser(rate, 337, 887, -.57, -.41)}},
          lowPass1(rate, 0.541196100146197), lowPass2(rate, 1.30656296487638) {
        forward = kiss_fftr_alloc(kSize, 0, nullptr, nullptr);
        inverse = kiss_fftr_alloc(kSize, 1, nullptr, nullptr);
        if (!forward || !inverse) {
            kiss_fftr_free(forward);
            kiss_fftr_free(inverse);
            throw std::bad_alloc();
        }
        // Four-fold sine/sine WOLA: sum of squared shifted windows equals 1.
        for (int n = 0; n < kSize; ++n)
            window[n] = std::sin(kPi * (n + .5) / kSize) / std::sqrt(2.0);
        reset();
    }

    ~Impl() { kiss_fftr_free(forward); kiss_fftr_free(inverse); }

    void reset() {
        history = {};
        ola = {};
        ready = {};
        covarianceHistory = {};
        filterHistory = {};
        bassDelay = {};
        filled = bassIndex = covarianceIndex = filterIndex = 0;
        covarianceCount = filterCount = 0;
        for (auto& diffuser : diffusers) diffuser.reset();
        lowPass1.reset();
        lowPass2.reset();
    }

    void process(const float* stereo, float* wave71, int frames, float* separated) {
        if (frames < 0 || frames > 1048576 || (frames && (!stereo || !wave71)))
            throw std::invalid_argument("Invalid PAE input/output buffer or frame count");
        for (int f = 0; f < frames; ++f) {
            const double l = sample(stereo[2 * f]), r = sample(stereo[2 * f + 1]);
            history[0][kSize - kHop + filled] = l;
            history[1][kSize - kHop + filled] = r;
            const double pL = ready[0][filled], pR = ready[1][filled];
            const double center = ready[2][filled];
            const double aL = ready[3][filled], aR = ready[4][filled];
            const double delayedBass = bassDelay[bassIndex];
            bassDelay[bassIndex] = .5 * (l + r);
            bassIndex = (bassIndex + 1) % kSize;

            float* out = wave71 + 8 * f;
            out[0] = float(pL - center + kAmbientFront * aL);
            out[1] = float(pR - center + kAmbientFront * aR);
            out[2] = float(kSqrtTwo * center);
            out[3] = float(kLfeGain * lowPass2.process(lowPass1.process(delayedBass)));
            out[4] = float(kAmbientSurround * diffusers[0].process(aL));
            out[5] = float(kAmbientSurround * diffusers[1].process(aR));
            out[6] = float(kAmbientSurround * diffusers[2].process(aL));
            out[7] = float(kAmbientSurround * diffusers[3].process(aR));
            if (separated) {
                separated[4 * f] = float(pL);
                separated[4 * f + 1] = float(pR);
                separated[4 * f + 2] = float(aL);
                separated[4 * f + 3] = float(aR);
            }
            if (++filled == kHop) {
                processHop();
                filled = 0;
            }
        }
    }

private:
    void processHop() {
        for (int ch = 0; ch < 2; ++ch) {
            for (int n = 0; n < kSize; ++n) fftInput[n] = history[ch][n] * window[n];
            kiss_fftr(forward, fftInput.data(), inputSpectrum[ch].data());
            std::memmove(history[ch].data(), history[ch].data() + kHop,
                         sizeof(double) * (kSize - kHop));
        }
        covarianceCount = std::min(covarianceCount + 1, kCovarianceFrames);
        filterCount = std::min(filterCount + 1, kFilterFrames);

        for (int bin = 0; bin < kBins; ++bin) {
            const Complex l(inputSpectrum[0][bin].r, inputSpectrum[0][bin].i);
            const Complex r(inputSpectrum[1][bin].r, inputSpectrum[1][bin].i);
            covarianceHistory[covarianceIndex][bin] = {std::norm(l), std::norm(r), l * std::conj(r)};
            Covariance covariance;
            for (int t = 0; t < covarianceCount; ++t) {
                const auto& c = covarianceHistory[t][bin];
                covariance.left += c.left;
                covariance.right += c.right;
                covariance.cross += c.cross;
            }
            covariance.left /= covarianceCount;
            covariance.right /= covarianceCount;
            covariance.cross /= covarianceCount;

            // Cx = Cp + sigma_A^2 I, Cp rank one. The smaller eigenvalue
            // estimates per-channel diffuse power. Gp = Cp * inverse(Cx)
            // simplifies to (Cx - lambda_min I) / lambda_max, including the
            // continuous rank-one limit. This avoids dividing by det(Cx),
            // which is zero for mono, hard panning, and perfect anti-phase.
            const double trace = covariance.left + covariance.right;
            const double discriminant = std::hypot(covariance.left - covariance.right,
                                                   2 * std::abs(covariance.cross));
            const double major = .5 * (trace + discriminant);
            const double minor = std::max(0.0, .5 * (trace - discriminant));
            Covariance filter;
            if (major > 1e-24) {
                filter.left = std::max(0.0, covariance.left - minor) / major;
                filter.right = std::max(0.0, covariance.right - minor) / major;
                filter.cross = covariance.cross / major;
            }
            filterHistory[filterIndex][bin] = filter;
            Covariance smooth;
            for (int t = 0; t < filterCount; ++t) {
                const auto& g = filterHistory[t][bin];
                smooth.left += g.left;
                smooth.right += g.right;
                smooth.cross += g.cross;
            }
            smooth.left /= filterCount;
            smooth.right /= filterCount;
            smooth.cross /= filterCount;
            const Complex pL = smooth.left * l + smooth.cross * r;
            const Complex pR = std::conj(smooth.cross) * l + smooth.right * r;
            // Complementary filters retain exact complex STFT reconstruction.
            const Complex aL = l - pL, aR = r - pR;

            // Only shared, positively coherent primary content is moved into
            // FC. Hard-panned and anti-phase primaries remain in FL/FR. The
            // extracted center is subtracted from both fronts, so conventional
            // -3 dB center folding restores the original primary stereo pair.
            const double uL = std::sqrt(std::max(0.0, smooth.left));
            const double uR = std::sqrt(std::max(0.0, smooth.right));
            const double crossAbs = std::abs(smooth.cross);
            const double phase = crossAbs > 1e-15 ? std::max(0.0, smooth.cross.real() / crossAbs) : 0;
            const double shared = uL + uR > 1e-15 ? std::min(uL, uR) / (uL + uR) : 0;
            const Complex center = (pL + pR) * (shared * phase * phase);
            const Complex values[kComponents] = {pL, pR, center, aL, aR};
            for (int ch = 0; ch < kComponents; ++ch)
                outputSpectrum[ch][bin] = {values[ch].real(),
                    (bin == 0 || bin == kBins - 1) ? 0.0 : values[ch].imag()};
        }
        covarianceIndex = (covarianceIndex + 1) % kCovarianceFrames;
        filterIndex = (filterIndex + 1) % kFilterFrames;
        for (int ch = 0; ch < kComponents; ++ch) {
            kiss_fftri(inverse, outputSpectrum[ch].data(), fftInput.data());
            for (int n = 0; n < kSize; ++n)
                ola[ch][n] += fftInput[n] * window[n] / kSize;
            std::copy_n(ola[ch].begin(), kHop, ready[ch].begin());
            std::memmove(ola[ch].data(), ola[ch].data() + kHop,
                         sizeof(double) * (kSize - kHop));
            std::fill(ola[ch].end() - kHop, ola[ch].end(), 0);
        }
    }

    const int sampleRate;
    kiss_fftr_cfg forward = nullptr, inverse = nullptr;
    std::array<double, kSize> window{}, fftInput{}, bassDelay{};
    std::array<std::array<double, kSize>, 2> history{};
    std::array<std::array<kiss_fft_cpx, kBins>, 2> inputSpectrum{};
    std::array<std::array<kiss_fft_cpx, kBins>, kComponents> outputSpectrum{};
    std::array<std::array<double, kSize>, kComponents> ola{};
    std::array<std::array<double, kHop>, kComponents> ready{};
    std::array<std::array<Covariance, kBins>, kCovarianceFrames> covarianceHistory{};
    std::array<std::array<Covariance, kBins>, kFilterFrames> filterHistory{};
    std::array<Diffuser, 4> diffusers;
    LowPass lowPass1, lowPass2;
    int filled = 0, bassIndex = 0, covarianceIndex = 0, filterIndex = 0;
    int covarianceCount = 0, filterCount = 0;
};

PaeUpmixer::PaeUpmixer(int sampleRate) : impl_(new Impl(sampleRate)) {}
PaeUpmixer::~PaeUpmixer() = default;
int PaeUpmixer::latencyFrames() const { return kSize; }
void PaeUpmixer::process(const float* stereo, float* wave71, int frames, float* primaryAmbient) {
    impl_->process(stereo, wave71, frames, primaryAmbient);
}
void PaeUpmixer::reset() { impl_->reset(); }

} // namespace spatial
