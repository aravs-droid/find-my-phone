// Host-side build of the app's frontend (same vendored sources, same config header as
// frontend_jni.cc) exposed over a plain C ABI for parity_check.py.
#include <cstdint>
#include <cstdlib>

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
}
#include "wakeword_frontend_config.h"

struct Handle {
    FrontendConfig config;
    FrontendState state;
};

extern "C" {

#ifdef _WIN32
#define API __declspec(dllexport)
#else
#define API
#endif

API void* wf_create() {
    auto* h = static_cast<Handle*>(calloc(1, sizeof(Handle)));
    wakeword::FillFrontendConfig(&h->config);
    if (!FrontendPopulateState(&h->config, &h->state, wakeword::kSampleRate)) {
        free(h);
        return nullptr;
    }
    return h;
}

// Same contract as nativeProcess: returns samples_read, writes n features to out.
API int wf_process(void* p, const int16_t* samples, int count, float* out, int* n) {
    auto* h = static_cast<Handle*>(p);
    size_t read = 0;
    FrontendOutput fo = FrontendProcessSamples(&h->state, samples, (size_t)count, &read);
    *n = 0;
    if (fo.size > 0 && fo.values) {
        *n = (int)fo.size;
        for (size_t i = 0; i < fo.size; ++i)
            out[i] = (float)(uint16_t)fo.values[i] * wakeword::kFloat32Scale;
    }
    return (int)read;
}

API void wf_destroy(void* p) {
    auto* h = static_cast<Handle*>(p);
    FrontendFreeStateContents(&h->state);
    free(h);
}

}
