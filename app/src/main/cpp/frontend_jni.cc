// JNI bridge to the TFLite Micro audio frontend (WAKE_WORD_INTEGRATION.md section 2).
//
// One native FrontendState per MicroFrontend instance, created once per listening
// session and reused for every 10ms chunk (pitfall #3). Output is read as uint16_t
// and scaled by 0.0390625 before it leaves native code (pitfalls #4 and #5), so
// Kotlin only ever sees final float features.

#include <jni.h>
#include <cstdint>
#include <cstdlib>

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
}
#include "wakeword_frontend_config.h"

namespace {

using wakeword::kFloat32Scale;
using wakeword::kNumChannels;
using wakeword::kSampleRate;

struct Handle {
    FrontendConfig config;
    FrontendState state;
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_net_media_wakeword_MicroFrontend_nativeCreate(JNIEnv*, jclass) {
    auto* h = static_cast<Handle*>(calloc(1, sizeof(Handle)));
    if (!h) return 0;

    wakeword::FillFrontendConfig(&h->config);
    if (!FrontendPopulateState(&h->config, &h->state, kSampleRate)) {
        free(h);
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

// Feeds `count` samples starting at `offset`. Writes up to 40 features into `out`.
// Returns (featureCount << 16) | samplesRead -- featureCount is 0 when the frontend
// is still accumulating a window ("not enough audio yet", not an error).
extern "C" JNIEXPORT jint JNICALL
Java_net_media_wakeword_MicroFrontend_nativeProcess(
        JNIEnv* env, jclass, jlong handle, jshortArray samples, jint offset,
        jint count, jfloatArray out) {
    auto* h = reinterpret_cast<Handle*>(handle);
    if (!h) return 0;

    jshort* pcm = env->GetShortArrayElements(samples, nullptr);
    size_t samples_read = 0;
    FrontendOutput fo = FrontendProcessSamples(
            &h->state, reinterpret_cast<const int16_t*>(pcm) + offset,
            static_cast<size_t>(count), &samples_read);
    env->ReleaseShortArrayElements(samples, pcm, JNI_ABORT);

    int n = 0;
    if (fo.size > 0 && fo.values != nullptr) {
        float features[kNumChannels];
        n = fo.size < kNumChannels ? static_cast<int>(fo.size) : kNumChannels;
        for (int i = 0; i < n; ++i) {
            features[i] = static_cast<float>(static_cast<uint16_t>(fo.values[i])) * kFloat32Scale;
        }
        env->SetFloatArrayRegion(out, 0, n, features);
    }
    return (n << 16) | static_cast<jint>(samples_read & 0xFFFF);
}

extern "C" JNIEXPORT void JNICALL
Java_net_media_wakeword_MicroFrontend_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    auto* h = reinterpret_cast<Handle*>(handle);
    if (!h) return;
    FrontendFreeStateContents(&h->state);
    free(h);
}
