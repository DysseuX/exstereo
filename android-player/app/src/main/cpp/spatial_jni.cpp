/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "spatial_engine.h"
#include "equalizer30.h"
#include <jni.h>

#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>

namespace {
struct Session {
    explicit Session(int rate, const std::string& sofa) : engine(rate, sofa), equalizer(rate) {}
    Session(int rate, const std::string& sofa, int renderer, int upmixer, const spatial::Settings& settings)
        : engine(rate, sofa, renderer, upmixer, settings), equalizer(rate) {}
    spatial::Engine engine;
    spatial::Equalizer30 equalizer;
    std::mutex mutex;
};
std::mutex sessionsMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> nextHandle{1};

std::shared_ptr<Session> session(jlong handle) {
    std::lock_guard<std::mutex> guard(sessionsMutex);
    const auto found = sessions.find(handle);
    if (found == sessions.end()) throw std::invalid_argument("Spatial engine is already released or was not created");
    return found->second;
}
void exception(JNIEnv* env, const char* message, const char* type = "java/lang/IllegalStateException") {
    if (!env->ExceptionCheck()) {
        jclass clazz = env->FindClass(type);
        if (clazz) env->ThrowNew(clazz, message);
    }
}

spatial::Settings liveSettings(JNIEnv* env, jfloatArray parameters) {
    // v1: 37, v2: 44, v3: 63. Existing offsets remain stable.
    if (!parameters) throw std::invalid_argument("Spatial parameters are required");
    const auto count = env->GetArrayLength(parameters);
    if (count != 37 && count != 44 && count != 63) throw std::invalid_argument("Expected 37, 44 or 63 spatial parameters");
    std::array<float, 63> values{};
    env->GetFloatArrayRegion(parameters, 0, count, values.data());
    if (env->ExceptionCheck()) throw std::invalid_argument("Cannot read spatial parameters");
    for (float value : values) if (!std::isfinite(value))
        throw std::invalid_argument("Spatial parameters must be finite");
    if (values[0] < 0 || values[0] > 2 || std::floor(values[0]) != values[0])
        throw std::invalid_argument("Invalid spatial mode");
    spatial::Settings settings;
    settings.mode = static_cast<int>(values[0]);
    settings.width = values[1];
    settings.surroundGain = values[2];
    settings.lfeGain = values[3];
    settings.virtualStereo = values[4] >= 0.5f;
    settings.hrtfBlend = values[5];
    settings.listenerYaw = values[6];
    settings.listenerPitch = values[7];
    settings.hrtfBalanceDb = values[8];
    settings.hrtfGainDb = values[9];
    for (size_t i = 0; i < 7; ++i) {
        settings.azimuths[i] = values[10 + 3 * i];
        settings.elevations[i] = values[11 + 3 * i];
        settings.distances[i] = values[12 + 3 * i];
    }
    for (size_t i = 0; i < 2; ++i) {
        settings.stereoAzimuths[i] = values[31 + 3 * i];
        settings.stereoElevations[i] = values[32 + 3 * i];
        settings.stereoDistances[i] = values[33 + 3 * i];
    }
    if (count >= 44) {
        if (values[37] < 0 || values[37] > (count == 63 ? 10 : 5) || std::floor(values[37]) != values[37])
            throw std::invalid_argument("Invalid widening algorithm");
        settings.wideningAlgorithm = static_cast<int>(values[37]);
        settings.wideningAmount = values[38];
        settings.widenerLow = values[39]; settings.widenerHigh = values[40];
        settings.widenerCrossover = values[41];
        settings.transientProtection = values[42] >= .5f;
        settings.widenerAmplitudePreserving = values[43] >= .5f;
    }
    if (count == 63) {
        settings.bandLowWidth = values[44]; settings.bandMidWidth = values[45]; settings.bandHighWidth = values[46];
        settings.bandLowCrossover = values[47]; settings.bandHighCrossover = values[48];
        settings.correlationGuard = values[49] >= .5f;
        settings.paeAmbientWidth = values[50]; settings.paeAmbienceGainDb = values[51];
        settings.sideSynthesisAmount = values[52]; settings.sideHighpass = values[53];
        settings.shuffleAmount = values[54]; settings.shuffleFrequency = values[55];
        settings.microCents = values[56]; settings.microDelayMs = values[57];
        settings.roomEnabled = values[58] >= .5f; settings.roomMix = values[59];
        settings.roomSize = values[60]; settings.roomDamping = values[61]; settings.roomDecay = values[62];
    }
    return settings;
}

jfloatArray processPcm(JNIEnv* env, jlong handle, jfloatArray input, jint channels,
                       const spatial::Settings& settings) {
    if (!input) throw std::invalid_argument("PCM is required");
    if (channels != 1 && channels != 2 && channels != 6 && channels != 8)
        throw std::invalid_argument("Expected 1, 2, 6 or 8 PCM channels");
    const jsize size = env->GetArrayLength(input);
    if (size % channels || size / channels > 1048576)
        throw std::invalid_argument("PCM must contain complete frames, at most 1048576 per call");
    std::vector<float> samples(static_cast<size_t>(size));
    if (size) env->GetFloatArrayRegion(input, 0, size, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    auto value = session(handle);
    std::lock_guard<std::mutex> guard(value->mutex);
    auto output = value->engine.process(samples.data(), size / channels, channels, settings);
    value->equalizer.process(output.data(), output.size() / 2);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (result && !output.empty()) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_fossify_musicplayer_spatial_NativeSpatialEngine_createLive(
        JNIEnv* env, jobject, jint rate, jstring sofaPath, jint renderer, jint upmixer,
        jfloatArray parameters) {
    try {
        const auto settings = liveSettings(env, parameters);
        std::string path;
        if (sofaPath) {
            const char* raw = env->GetStringUTFChars(sofaPath, nullptr);
            if (!raw) return 0;
            path = raw;
            env->ReleaseStringUTFChars(sofaPath, raw);
        }
        auto value = std::make_shared<Session>(rate, path, renderer, upmixer, settings);
        const jlong handle = nextHandle.fetch_add(1);
        std::lock_guard<std::mutex> guard(sessionsMutex);
        sessions.emplace(handle, std::move(value));
        return handle;
    } catch (const std::exception& e) { exception(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_fossify_musicplayer_spatial_NativeSpatialEngine_processLive(
        JNIEnv* env, jobject, jlong handle, jfloatArray input, jint channels,
        jfloatArray parameters) {
    try {
        return processPcm(env, handle, input, channels, liveSettings(env, parameters));
    } catch (const std::bad_alloc&) {
        exception(env, "Not enough memory for spatial audio", "java/lang/OutOfMemoryError");
    } catch (const std::invalid_argument& e) {
        exception(env, e.what(), "java/lang/IllegalArgumentException");
    } catch (const std::exception& e) { exception(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_fossify_musicplayer_spatial_NativeSpatialEngine_create(JNIEnv* env, jobject, jint rate, jstring sofaPath) {
    try {
        std::string path;
        if (sofaPath) {
            const char* raw = env->GetStringUTFChars(sofaPath, nullptr);
            if (!raw) return 0;
            path = raw;
            env->ReleaseStringUTFChars(sofaPath, raw);
        }
        auto value = std::make_shared<Session>(rate, path);
        const jlong handle = nextHandle.fetch_add(1);
        std::lock_guard<std::mutex> guard(sessionsMutex);
        sessions.emplace(handle, std::move(value));
        return handle;
    } catch (const std::exception& e) { exception(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_fossify_musicplayer_spatial_NativeSpatialEngine_latencyFrames(JNIEnv* env, jobject, jlong handle) {
    try { return session(handle)->engine.latencyFrames(); }
    catch (const std::exception& e) { exception(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_fossify_musicplayer_spatial_NativeSpatialEngine_createConfigured(
        JNIEnv* env, jobject, jint rate, jstring sofaPath, jint renderer, jint upmixer,
        jfloatArray azimuths, jfloatArray elevations) {
    try {
        if (!azimuths || !elevations || env->GetArrayLength(azimuths) != 7 || env->GetArrayLength(elevations) != 7)
            throw std::invalid_argument("Exactly seven virtual speaker positions are required");
        spatial::Settings settings;
        env->GetFloatArrayRegion(azimuths, 0, 7, settings.azimuths.data());
        env->GetFloatArrayRegion(elevations, 0, 7, settings.elevations.data());
        if (env->ExceptionCheck()) return 0;
        std::string path;
        if (sofaPath) {
            const char* raw = env->GetStringUTFChars(sofaPath, nullptr);
            if (!raw) return 0;
            path = raw;
            env->ReleaseStringUTFChars(sofaPath, raw);
        }
        auto value = std::make_shared<Session>(rate, path, renderer, upmixer, settings);
        const jlong handle = nextHandle.fetch_add(1);
        std::lock_guard<std::mutex> guard(sessionsMutex);
        sessions.emplace(handle, std::move(value));
        return handle;
    } catch (const std::exception& e) { exception(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_fossify_musicplayer_spatial_NativeSpatialEngine_process(
        JNIEnv* env, jobject, jlong handle, jfloatArray input, jint channels,
        jint mode, jfloat width, jfloat surroundGain, jfloat lfeGain,
        jfloatArray azimuths, jfloatArray elevations) {
    try {
        if (!input || !azimuths || !elevations) throw std::invalid_argument("PCM and speaker positions are required");
        if (channels != 1 && channels != 2 && channels != 6 && channels != 8)
            throw std::invalid_argument("Expected 1, 2, 6 or 8 PCM channels");
        const jsize size = env->GetArrayLength(input);
        if (size % channels || size / channels > 1048576)
            throw std::invalid_argument("PCM must contain complete frames, at most 1048576 per call");
        if (env->GetArrayLength(azimuths) != 7 || env->GetArrayLength(elevations) != 7)
            throw std::invalid_argument("Exactly seven virtual speaker positions are required");
        spatial::Settings settings;
        settings.mode = mode;
        settings.width = width;
        settings.surroundGain = surroundGain;
        settings.lfeGain = lfeGain;
        env->GetFloatArrayRegion(azimuths, 0, 7, settings.azimuths.data());
        env->GetFloatArrayRegion(elevations, 0, 7, settings.elevations.data());
        std::vector<float> samples(static_cast<size_t>(size));
        if (size) env->GetFloatArrayRegion(input, 0, size, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        auto value = session(handle);
        std::lock_guard<std::mutex> guard(value->mutex);
        auto output = value->engine.process(samples.data(), size / channels, channels, settings);
        value->equalizer.process(output.data(), output.size() / 2);
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (result && !output.empty()) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        return result;
    } catch (const std::bad_alloc&) {
        exception(env, "Not enough memory for spatial audio", "java/lang/OutOfMemoryError");
    } catch (const std::invalid_argument& e) {
        exception(env, e.what(), "java/lang/IllegalArgumentException");
    } catch (const std::exception& e) { exception(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossify_musicplayer_spatial_NativeSpatialEngine_reset(JNIEnv* env, jobject, jlong handle) {
    try {
        auto value = session(handle);
        std::lock_guard<std::mutex> guard(value->mutex);
        value->engine.reset();
        value->equalizer.reset();
    } catch (const std::exception& e) { exception(env, e.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossify_musicplayer_spatial_NativeSpatialEngine_release(JNIEnv*, jobject, jlong handle) {
    // A shared_ptr held by process() keeps an in-flight call alive. Repeated
    // release calls are safe and handles are never interpreted as raw pointers.
    std::lock_guard<std::mutex> guard(sessionsMutex);
    sessions.erase(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_org_fossify_musicplayer_spatial_NativeSpatialEngine_setEqualizer(
        JNIEnv* env, jobject, jlong handle, jfloatArray gains) {
    try {
        if (!gains || env->GetArrayLength(gains) != spatial::Equalizer30::kBands)
            throw std::invalid_argument("Exactly 30 EQ gains are required");
        std::array<float, spatial::Equalizer30::kBands> values{};
        env->GetFloatArrayRegion(gains, 0, values.size(), values.data());
        if (env->ExceptionCheck()) return;
        auto value = session(handle);
        std::lock_guard<std::mutex> guard(value->mutex);
        value->equalizer.setGains(values);
    } catch (const std::invalid_argument& e) {
        exception(env, e.what(), "java/lang/IllegalArgumentException");
    } catch (const std::exception& e) { exception(env, e.what()); }
}
