/*
 * vrwma: a thin wrapper over FFmpeg's libavcodec WMA decoders (wmav1, wmav2, wmapro, wmalossless),
 * for VibeRadio's WMA extractor. LGPL v2.1 or later, like FFmpeg; see docs/wma.md.
 *
 * The app reads the ASF container itself (AsfParser.kt) and hands whole media objects here, one
 * per call: for WMA v1/v2 each is one block_align-sized superframe. Output is interleaved 32-bit
 * float PCM, whatever the codec's native sample format.
 *
 * The core (vrwma_*) has no JNI; the JNI glue at the foot is compiled only with -DVRWMA_JNI.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>

typedef struct VrWma {
    AVCodecContext *ctx;
    AVPacket *pkt;
    AVFrame *frame;
} VrWma;

/* ASF wFormatTag values. */
#define TAG_WMA1 0x160
#define TAG_WMA2 0x161
#define TAG_WMAPRO 0x162
#define TAG_WMALOSSLESS 0x163

static const AVCodec *find_codec(int format_tag)
{
    switch (format_tag) {
    case TAG_WMA1: return avcodec_find_decoder(AV_CODEC_ID_WMAV1);
    case TAG_WMA2: return avcodec_find_decoder(AV_CODEC_ID_WMAV2);
    case TAG_WMAPRO: return avcodec_find_decoder(AV_CODEC_ID_WMAPRO);
    case TAG_WMALOSSLESS: return avcodec_find_decoder(AV_CODEC_ID_WMALOSSLESS);
    default: return NULL;
    }
}

/* 1 if the codec for [format_tag] is built in. */
int vrwma_supported(int format_tag)
{
    return find_codec(format_tag) != NULL;
}

void vrwma_close(VrWma *w)
{
    if (!w) return;
    av_frame_free(&w->frame);
    av_packet_free(&w->pkt);
    avcodec_free_context(&w->ctx);
    free(w);
}

/* Opens a decoder from an ASF audio stream's WAVEFORMATEX fields; NULL on failure. */
VrWma *vrwma_open(int format_tag, int channels, int sample_rate, int bit_rate, int block_align,
                  int bits_per_sample, const uint8_t *extradata, int extradata_size)
{
    const AVCodec *codec = find_codec(format_tag);
    if (!codec || channels <= 0 || sample_rate <= 0) return NULL;
    VrWma *w = calloc(1, sizeof(*w));
    if (!w) return NULL;
    w->ctx = avcodec_alloc_context3(codec);
    w->pkt = av_packet_alloc();
    w->frame = av_frame_alloc();
    if (!w->ctx || !w->pkt || !w->frame) goto fail;
    w->ctx->sample_rate = sample_rate;
    av_channel_layout_default(&w->ctx->ch_layout, channels);
    w->ctx->bit_rate = bit_rate;
    w->ctx->block_align = block_align;
    w->ctx->bits_per_coded_sample = bits_per_sample;
    if (extradata_size > 0 && extradata) {
        w->ctx->extradata = av_mallocz((size_t)extradata_size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!w->ctx->extradata) goto fail;
        memcpy(w->ctx->extradata, extradata, (size_t)extradata_size);
        w->ctx->extradata_size = extradata_size;
    }
    if (avcodec_open2(w->ctx, codec, NULL) < 0) goto fail;
    return w;
fail:
    vrwma_close(w);
    return NULL;
}

/* Drops the decoder's state (after a seek). */
void vrwma_flush(VrWma *w)
{
    if (w) avcodec_flush_buffers(w->ctx);
}

static float sample_at(const AVFrame *f, int channel, int i)
{
    switch (f->format) {
    case AV_SAMPLE_FMT_FLTP: return ((const float *)f->extended_data[channel])[i];
    case AV_SAMPLE_FMT_FLT: return ((const float *)f->extended_data[0])[i * f->ch_layout.nb_channels + channel];
    case AV_SAMPLE_FMT_S16P: return ((const int16_t *)f->extended_data[channel])[i] / 32768.0f;
    case AV_SAMPLE_FMT_S16: return ((const int16_t *)f->extended_data[0])[i * f->ch_layout.nb_channels + channel] / 32768.0f;
    case AV_SAMPLE_FMT_S32P: return (float)(((const int32_t *)f->extended_data[channel])[i] / 2147483648.0);
    case AV_SAMPLE_FMT_S32: return (float)(((const int32_t *)f->extended_data[0])[i * f->ch_layout.nb_channels + channel] / 2147483648.0);
    default: return 0.0f;
    }
}

/*
 * Decodes one media object into [out] (room for [out_floats] floats, interleaved). Returns the
 * number of floats written (0 if the packet produced no audio yet), -1 on a decode error (the
 * caller carries on with the next packet), -2 if [out] is too small.
 */
int vrwma_decode(VrWma *w, const uint8_t *data, int size, float *out, int out_floats)
{
    if (!w || size <= 0) return -1;
    if (av_new_packet(w->pkt, size) < 0) return -1;
    memcpy(w->pkt->data, data, (size_t)size);
    int r = avcodec_send_packet(w->ctx, w->pkt);
    av_packet_unref(w->pkt);
    if (r < 0 && r != AVERROR(EAGAIN)) return -1;
    int written = 0;
    for (;;) {
        r = avcodec_receive_frame(w->ctx, w->frame);
        if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) break;
        if (r < 0) return written > 0 ? written : -1;
        int channels = w->frame->ch_layout.nb_channels;
        int count = w->frame->nb_samples * channels;
        if (written + count > out_floats) {
            av_frame_unref(w->frame);
            return -2;
        }
        for (int i = 0; i < w->frame->nb_samples; i++)
            for (int c = 0; c < channels; c++) out[written++] = sample_at(w->frame, c, i);
        av_frame_unref(w->frame);
    }
    return written;
}

#ifdef VRWMA_JNI
#include <jni.h>

/* Kotlin: app.viberadio.playback.wma.WmaNative (static native methods). */

JNIEXPORT jboolean JNICALL Java_app_viberadio_playback_wma_WmaNative_supported(JNIEnv *env, jclass cls, jint tag)
{
    (void)env; (void)cls;
    return vrwma_supported(tag) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_app_viberadio_playback_wma_WmaNative_open(
    JNIEnv *env, jclass cls, jint tag, jint channels, jint rate, jint bitRate, jint blockAlign, jint bits, jbyteArray extra)
{
    (void)cls;
    jbyte *bytes = NULL;
    jsize n = 0;
    if (extra) {
        n = (*env)->GetArrayLength(env, extra);
        bytes = (*env)->GetByteArrayElements(env, extra, NULL);
    }
    VrWma *w = vrwma_open(tag, channels, rate, bitRate, blockAlign, bits, (const uint8_t *)bytes, n);
    if (bytes) (*env)->ReleaseByteArrayElements(env, extra, bytes, JNI_ABORT);
    return (jlong)(intptr_t)w;
}

/* [out] is a direct ByteBuffer of native-endian floats. */
JNIEXPORT jint JNICALL Java_app_viberadio_playback_wma_WmaNative_decode(
    JNIEnv *env, jclass cls, jlong handle, jbyteArray data, jint size, jobject out)
{
    (void)cls;
    float *dst = (float *)(*env)->GetDirectBufferAddress(env, out);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, out);
    if (!dst) return -1;
    jbyte *src = (*env)->GetByteArrayElements(env, data, NULL);
    if (!src) return -1;
    int r = vrwma_decode((VrWma *)(intptr_t)handle, (const uint8_t *)src, size, dst, (int)(capacity / 4));
    (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT);
    return r;
}

JNIEXPORT void JNICALL Java_app_viberadio_playback_wma_WmaNative_flush(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env; (void)cls;
    vrwma_flush((VrWma *)(intptr_t)handle);
}

JNIEXPORT void JNICALL Java_app_viberadio_playback_wma_WmaNative_close(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env; (void)cls;
    vrwma_close((VrWma *)(intptr_t)handle);
}
#endif
