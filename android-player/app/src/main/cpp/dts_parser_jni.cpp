/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/mem.h>
}
namespace {
constexpr int kMaxBuffered = 4 * 1024 * 1024;
struct Parser {
    AVCodecParserContext* parser = av_parser_init(AV_CODEC_ID_DTS);
    AVCodecContext* context = avcodec_alloc_context3(nullptr);
    int channels = 0, rate = 0, buffered = 0;
    ~Parser() { if (parser) av_parser_close(parser); avcodec_free_context(&context); }
};
void fail(JNIEnv* env, const char* message) {
    auto cls = env->FindClass("java/io/IOException");
    if (cls) env->ThrowNew(cls, message);
}
bool probe(Parser* state, const uint8_t* data, int size) {
    const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_DTS);
    AVCodecContext* context = avcodec_alloc_context3(codec);
    AVPacket* packet = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    bool ok = false;
    if (codec && context && packet && frame) {
        context->thread_count = 1;
        if (avcodec_open2(context, codec, nullptr) >= 0 && av_new_packet(packet, size) >= 0) {
            memcpy(packet->data, data, size);
            if (avcodec_send_packet(context, packet) >= 0 && avcodec_receive_frame(context, frame) >= 0) {
                state->channels = frame->ch_layout.nb_channels;
                state->rate = frame->sample_rate;
                ok = state->channels >= 1 && state->channels <= 8 && state->rate > 0;
            }
        }
    }
    av_frame_free(&frame); av_packet_free(&packet); avcodec_free_context(&context);
    return ok;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_fossify_musicplayer_playback_player_NativeDtsParser_create(JNIEnv* env, jobject) {
    auto state = new (std::nothrow) Parser();
    if (!state || !state->parser || !state->context) { delete state; fail(env, "Cannot initialize DTS parser"); return 0; }
    state->context->codec_id = AV_CODEC_ID_DTS;
    return reinterpret_cast<jlong>(state);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_fossify_musicplayer_playback_player_NativeDtsParser_parse(JNIEnv* env, jobject, jlong handle,
    jbyteArray input, jint offset, jint length, jlong position, jlongArray info) {
    auto state = reinterpret_cast<Parser*>(handle);
    if (!state || !input || !info || env->GetArrayLength(info) < 6 || offset < 0 || length < 0 ||
        offset > env->GetArrayLength(input) || length > env->GetArrayLength(input) - offset || length > 65536) {
        fail(env, "Invalid DTS parser arguments"); return nullptr;
    }
    try {
        std::vector<uint8_t> bytes(length + AV_INPUT_BUFFER_PADDING_SIZE, 0);
        if (length) env->GetByteArrayRegion(input, offset, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        uint8_t* packet = nullptr;
        int size = 0;
        int consumed = av_parser_parse2(state->parser, state->context, &packet, &size,
            length ? bytes.data() : nullptr, length, AV_NOPTS_VALUE, AV_NOPTS_VALUE, position);
        if (consumed < 0 || consumed > length || size < 0 || size > kMaxBuffered) {
            fail(env, "Invalid DTS frame"); return nullptr;
        }
        state->buffered += consumed;
        if (state->buffered > kMaxBuffered) { fail(env, "DTS frame exceeds size limit"); return nullptr; }
        if (size && !state->channels && !probe(state, packet, size)) {
            fail(env, "Cannot decode DTS/DTS-HD header (supported: 1–8 channels)"); return nullptr;
        }
        if (size) state->buffered = 0;
        // Duration is in the parser context's sample-rate time base, including HD/core rate changes.
        jlong fields[] = {consumed, state->parser->duration, state->context->sample_rate, state->channels, state->rate, state->parser->frame_offset};
        env->SetLongArrayRegion(info, 0, 6, fields);
        if (!size || env->ExceptionCheck()) return nullptr;
        auto result = env->NewByteArray(size);
        if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<jbyte*>(packet));
        return result;
    } catch (const std::bad_alloc&) { fail(env, "DTS parser memory limit"); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_org_fossify_musicplayer_playback_player_NativeDtsParser_release(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Parser*>(handle);
}
