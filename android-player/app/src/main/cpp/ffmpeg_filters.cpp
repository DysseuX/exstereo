/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "ffmpeg_filters.h"
extern "C" {
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
}
#include <algorithm>
#include <cmath>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <locale>
#include <map>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>
#if defined(__linux__)
#include <sys/resource.h>
#endif

namespace spatial {
namespace {
void check(int code, const char* operation) {
    if (code < 0) {
        char message[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(code, message, sizeof(message));
        throw std::runtime_error(std::string("FFmpeg: ") + operation + ": " + message);
    }
}
class Graph {
public:
    Graph(int rate, int inputs, int outputs, const char* filter,
          const std::map<std::string, std::string>& options) : rate_(rate), inputs_(inputs), outputs_(outputs) {
        graph_ = avfilter_graph_alloc();
        if (!graph_) throw std::bad_alloc();
        graph_->nb_threads = 1;
        graph_->thread_type = 0;
        try {
            source_ = add("abuffer", "input", {{"sample_rate", std::to_string(rate)},
                {"time_base", "1/" + std::to_string(rate)}, {"sample_fmt", "fltp"},
                {"channel_layout", inputs == 2 ? "stereo" : "7.1"}});
            auto effect = add(filter, "effect", options);
            auto format = add("aformat", "format", {{"sample_fmts", "fltp"},
                {"sample_rates", std::to_string(rate)}, {"channel_layouts", outputs == 2 ? "stereo" : "7.1"}});
            sink_ = add("abuffersink", "output", {});
            check(avfilter_link(source_, 0, effect, 0), "input link");
            check(avfilter_link(effect, 0, format, 0), "effect link");
            check(avfilter_link(format, 0, sink_, 0), "output link");
            check(avfilter_graph_config(graph_, nullptr), "graph configuration");
            // aresample may convert FLTP to the packed FLT/DBL required by the
            // widening filters and back, but must not change the PCM rate or
            // buffer samples. The local sofalizer patch resamples only the IR.
            if (av_buffersink_get_sample_rate(sink_) != rate) throw std::runtime_error("FFmpeg changed PCM sample rate");
        } catch (...) { avfilter_graph_free(&graph_); throw; }
    }
    ~Graph() { avfilter_graph_free(&graph_); }
    void process(const float* input, float* output, int frames, bool omitLfe = false) {
        if (frames != 1024 || !input || !output) throw std::invalid_argument("FFmpeg requires one 1024-frame block");
        AVFrame* frame = av_frame_alloc();
        if (!frame) throw std::bad_alloc();
        try {
            frame->format = AV_SAMPLE_FMT_FLTP;
            frame->sample_rate = rate_;
            frame->nb_samples = frames;
            frame->pts = pts_;
            av_channel_layout_default(&frame->ch_layout, inputs_);
            check(av_frame_get_buffer(frame, 0), "input allocation");
            for (int c = 0; c < inputs_; ++c) {
                auto* plane = reinterpret_cast<float*>(frame->extended_data[c]);
                for (int i = 0; i < frames; ++i) plane[i] = omitLfe && c == 3 ? 0 : input[i * inputs_ + c];
            }
            check(av_buffersrc_add_frame_flags(source_, frame, 0), "submit PCM");
            pts_ += frames;
            av_frame_unref(frame);
            int written = 0;
            while (true) {
                int ret = av_buffersink_get_frame(sink_, frame);
                if (ret == AVERROR(EAGAIN)) break;
                check(ret, "receive PCM");
                if (frame->format != AV_SAMPLE_FMT_FLTP || frame->ch_layout.nb_channels != outputs_ ||
                    frame->sample_rate != rate_ || written + frame->nb_samples > frames)
                    throw std::runtime_error("FFmpeg output format or duration changed");
                for (int c = 0; c < outputs_; ++c) {
                    const auto* plane = reinterpret_cast<const float*>(frame->extended_data[c]);
                    for (int i = 0; i < frame->nb_samples; ++i) output[(written + i) * outputs_ + c] = plane[i];
                }
                written += frame->nb_samples;
                av_frame_unref(frame);
            }
            if (written != frames) throw std::runtime_error("FFmpeg unexpectedly buffered PCM");
            av_frame_free(&frame);
        } catch (...) { av_frame_free(&frame); throw; }
    }
private:
    AVFilterContext* add(const char* filter, const char* name, const std::map<std::string, std::string>& options) {
        const auto* definition = avfilter_get_by_name(filter);
        if (!definition) throw std::runtime_error(std::string("FFmpeg filter missing: ") + filter);
        auto* node = avfilter_graph_alloc_filter(graph_, definition, name);
        if (!node) throw std::bad_alloc();
        for (const auto& option : options)
            check(av_opt_set(node, option.first.c_str(), option.second.c_str(), AV_OPT_SEARCH_CHILDREN), option.first.c_str());
        check(avfilter_init_str(node, nullptr), filter);
        return node;
    }
    AVFilterGraph* graph_ = nullptr;
    AVFilterContext *source_ = nullptr, *sink_ = nullptr;
    int rate_, inputs_, outputs_;
    int64_t pts_ = 0;
};
}

class FfmpegUpmixer::Impl {
public:
    explicit Impl(int rate) : rate(rate) { reset(); }
    void reset() {
        graph.reset(new Graph(rate, 2, 8, "surround", {{"chl_in", "stereo"}, {"chl_out", "7.1"},
            {"win_size", "1024"}, {"win_func", "hann"}, {"overlap", "0.5"},
            {"lfe_low", "80"}, {"lfe_high", "120"}, {"lfe_mode", "add"}, {"lfe_out", "0.316227766"}}));
    }
    int rate;
    std::unique_ptr<Graph> graph;
};
FfmpegUpmixer::FfmpegUpmixer(int rate) : impl_(new Impl(rate)) {}
FfmpegUpmixer::~FfmpegUpmixer() = default;
void FfmpegUpmixer::process(const float* in, float* out, int frames) { impl_->graph->process(in, out, frames); }
void FfmpegUpmixer::reset() { impl_->reset(); }

class FfmpegWidener::Impl {
public:
    Impl(int rate, int algorithm) : rate(rate), algorithm(algorithm) {
        if (rate < 8000 || rate > 96000) throw std::invalid_argument("Unsupported widening sample rate");
        if (algorithm < 1 || algorithm > 3) throw std::invalid_argument("Invalid FFmpeg widening algorithm");
        reset();
    }
    void reset() {
        // All options are fixed and local. Live amount and algorithm crossfades
        // happen outside the graph, including Haas (no runtime option support).
        // Disable extrastereo's hard clipping: Engine has a linked peak limiter.
        switch (algorithm) {
        case 1:
            graph.reset(new Graph(rate, 2, 2, "extrastereo", {{"m", "2"}, {"c", "0"}}));
            break;
        case 2:
            graph.reset(new Graph(rate, 2, 2, "stereowiden", {{"delay", "12"},
                {"feedback", "0.15"}, {"crossfeed", "0.15"}, {"drymix", "1"}}));
            break;
        case 3:
            graph.reset(new Graph(rate, 2, 2, "haas", {{"level_in", "1"}, {"level_out", "1"},
                {"side_gain", "0.35"}, {"middle_source", "mid"}, {"middle_phase", "0"},
                {"left_delay", "2.05"}, {"left_balance", "-1"}, {"left_gain", "1"}, {"left_phase", "0"},
                {"right_delay", "2.12"}, {"right_balance", "1"}, {"right_gain", "1"}, {"right_phase", "1"}}));
            break;
        }
    }
    int rate, algorithm;
    std::unique_ptr<Graph> graph;
};
FfmpegWidener::FfmpegWidener(int rate, int algorithm) : impl_(new Impl(rate, algorithm)) {}
FfmpegWidener::~FfmpegWidener() = default;
void FfmpegWidener::process(const float* in, float* out, int frames) { impl_->graph->process(in, out, frames); }
void FfmpegWidener::reset() { impl_->reset(); }

class SofaRenderer::Impl {
public:
    Impl(int rate, const std::string& sofa, const std::array<float, 7>& az, const std::array<float, 7>& el)
        : rate(rate), sofa(sofa), requested{az, el}, fadeFrames(std::max(1, rate / 20)) {
        if (sofa.empty()) throw std::invalid_argument("sofalizer requires a SOFA profile");
        requested = normalized(requested);
        active = makeGraph(requested);
        startWorker();
    }
    ~Impl() { stopWorker(); }

    struct Directions {
        std::array<float, 7> az, el;
        bool operator==(const Directions& other) const { return az == other.az && el == other.el; }
    };
    static void validate(const Directions& directions) {
        for (int i = 0; i < 7; ++i)
            if (!std::isfinite(directions.az[i]) || !std::isfinite(directions.el[i]))
                throw std::invalid_argument("Invalid speaker position");
    }
    static Directions normalized(Directions directions) {
        validate(directions);
        for (int i = 0; i < 7; ++i) {
            // sofalizer samples integer-degree coordinates. Avoid repeatedly
            // reloading an identical IR while Engine's smoother approaches its
            // target in ever smaller fractional steps. +/-90 is deliberately
            // excluded: upstream applies elevation % 90 during lookup.
            directions.az[i] = std::round(std::remainder(directions.az[i], 360.f));
            directions.el[i] = std::round(std::max(-89.f, std::min(89.f, directions.el[i])));
        }
        return directions;
    }
    std::unique_ptr<Graph> makeGraph(const Directions& directions) const {
        const char* names[] = {"FL", "FR", "FC", "BL", "BR", "SL", "SR"};
        std::ostringstream positions;
        positions.imbue(std::locale::classic());
        for (int i = 0; i < 7; ++i) {
            // SOFA positive azimuth points left, our UI positive points right.
            positions << (i ? "|" : "") << names[i] << ' ' << -directions.az[i] << ' ' << directions.el[i];
        }
        return std::unique_ptr<Graph>(new Graph(rate, 8, 2, "sofalizer", {{"sofa", sofa},
            {"sample_rate", std::to_string(rate)}, {"speakers", positions.str()}, {"type", "freq"},
            {"framesize", "1024"}, {"interpolate", "1"}, {"normalize", "1"}, {"gain", "24"}}));
    }

    void request(const std::array<float, 7>& az, const std::array<float, 7>& el) {
        const Directions next = normalized({az, el});
        // The worker never holds this mutex while loading SOFA, constructing,
        // convolving or freeing a graph. No unbounded audio-thread wait.
        std::lock_guard<std::mutex> guard(mutex);
        if (!(next == requested)) {
            requested = next;
            ++requestedRevision;
            condition.notify_one();
        }
    }
    void render(const float* in, float* out, int frames) {
        if (frames != 1024 || !in || !out) throw std::invalid_argument("FFmpeg requires one 1024-frame block");
        {
            std::lock_guard<std::mutex> guard(mutex);
            if (ready) {
                // slotOccupied prevents another graph from being built until
                // this transition completes and its predecessor is retired.
                transition = std::move(ready);
                warmupBlocks = 2;
                fadePosition = 0;
            }
        }
        active->process(in, out, frames, true);
        if (!transition) return;
        try {
            transition->process(in, transitionOutput.data(), frames, true);
        } catch (const std::exception& error) {
            finishTransition(false, error.what());
            return; // A failed new filter cannot interrupt the working filter.
        } catch (...) {
            finishTransition(false, "Unknown sofalizer transition error");
            return;
        }
        if (warmupBlocks > 0) {
            --warmupBlocks;
            return;
        }
        for (int i = 0; i < frames; ++i) {
            // Linear, not equal-power: correlated filters must not produce a
            // +3 dB bump halfway through a small positional adjustment.
            const float mix = std::min(1.f, static_cast<float>(fadePosition + i + 1) / fadeFrames);
            for (int ear = 0; ear < 2; ++ear) {
                const int index = i * 2 + ear;
                out[index] += mix * (transitionOutput[index] - out[index]);
            }
        }
        fadePosition += frames;
        if (fadePosition >= fadeFrames) finishTransition(true, "");
    }
    void finishTransition(bool accept, const char* error) {
        std::unique_ptr<Graph> old;
        if (accept) {
            old = std::move(active);
            active = std::move(transition);
        } else old = std::move(transition);
        std::lock_guard<std::mutex> guard(mutex);
        retired = std::move(old);
        slotOccupied = false;
        updateError = error;
        condition.notify_all();
    }
    void startWorker() {
        worker = std::thread([this] { work(); });
    }
    void stopWorker() {
        {
            std::lock_guard<std::mutex> guard(mutex);
            stopping = true;
            condition.notify_all();
        }
        if (worker.joinable()) worker.join();
    }
    void work() {
#if defined(__linux__)
        // SOFA construction must not inherit the audio thread's high priority.
        // Linux/Android nice is per-thread; failure leaves normal scheduling.
        setpriority(PRIO_PROCESS, 0, 5);
#endif
        std::unique_lock<std::mutex> guard(mutex);
        for (;;) {
            condition.wait(guard, [this] {
                return stopping || retired || (!slotOccupied && handledRevision != requestedRevision);
            });
            if (stopping) return;
            if (retired) {
                auto old = std::move(retired);
                guard.unlock();
                old.reset(); // Large SOFA/FFT allocations are freed off audio.
                guard.lock();
                continue;
            }
            const Directions next = requested;
            const auto revision = requestedRevision;
            slotOccupied = true;
            preparing = true;
            guard.unlock();
            std::unique_ptr<Graph> prepared;
            std::string error;
            try { prepared = makeGraph(next); }
            catch (const std::exception& problem) { error = problem.what(); }
            catch (...) { error = "Unknown sofalizer preparation error"; }
            guard.lock();
            preparing = false;
            handledRevision = revision;
            if (stopping) return;
            updateError = error;
            if (prepared) ready = std::move(prepared);
            else slotOccupied = false;
            // Even if a newer request arrived during loading, hand this graph
            // over first. Discarding every obsolete graph would starve a drag
            // gesture that updates more frequently than SOFA can be loaded.
            condition.notify_all();
        }
    }
    void reset() {
        // reset/destruction, like render, are externally serialized by Engine.
        // Join before clearing any state touched by the preparation worker.
        stopWorker();
        transition.reset();
        ready.reset();
        retired.reset();
        auto fresh = makeGraph(requested);
        active = std::move(fresh);
        requestedRevision = handledRevision = 0;
        slotOccupied = preparing = stopping = false;
        updateError.clear();
        startWorker();
    }
    bool waitForPreparation(int timeoutMs) {
        std::unique_lock<std::mutex> guard(mutex);
        return condition.wait_for(guard, std::chrono::milliseconds(std::max(0, timeoutMs)), [this] {
            return stopping || ready || (!preparing && (slotOccupied || handledRevision == requestedRevision));
        });
    }
    bool isUpdating() const {
        std::lock_guard<std::mutex> guard(mutex);
        return slotOccupied || handledRevision != requestedRevision;
    }
    std::string lastUpdateError() const {
        std::lock_guard<std::mutex> guard(mutex);
        return updateError;
    }
    int rate;
    std::string sofa;
    Directions requested;
    int fadeFrames, fadePosition = 0, warmupBlocks = 0;
    std::array<float, 2048> transitionOutput{};
    // active/transition are exclusively audio-owned. ready/retired move across
    // the mutex; slotOccupied is true throughout prepare, ready and transition.
    std::unique_ptr<Graph> active, transition, ready, retired;
    mutable std::mutex mutex;
    std::condition_variable condition;
    std::thread worker;
    uint64_t requestedRevision = 0, handledRevision = 0;
    bool stopping = false, slotOccupied = false, preparing = false;
    std::string updateError;
};
SofaRenderer::SofaRenderer(int rate, const std::string& sofa, const std::array<float, 7>& az,
                         const std::array<float, 7>& el) : impl_(new Impl(rate, sofa, az, el)) {}
SofaRenderer::~SofaRenderer() = default;
void SofaRenderer::render(const float* in, float* out, int frames) { impl_->render(in, out, frames); }
void SofaRenderer::render(const float* in, float* out, int frames, const std::array<float, 7>& az,
                          const std::array<float, 7>& el) {
    impl_->request(az, el);
    impl_->render(in, out, frames);
}
void SofaRenderer::reset() { impl_->reset(); }
bool SofaRenderer::waitForPreparation(int timeoutMs) { return impl_->waitForPreparation(timeoutMs); }
bool SofaRenderer::isUpdating() const { return impl_->isUpdating(); }
std::string SofaRenderer::lastUpdateError() const { return impl_->lastUpdateError(); }
}
