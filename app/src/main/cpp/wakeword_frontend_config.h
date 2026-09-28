// Frontend config for the wake-word model (WAKE_WORD_INTEGRATION.md section 2).
// Shared by frontend_jni.cc (the app) and tools/parity (the host-side check against
// pymicro_features), so both exercise exactly the same settings.
#pragma once

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"
}

namespace wakeword {

constexpr int kSampleRate = 16000;
constexpr int kNumChannels = 40;
constexpr float kFloat32Scale = 0.0390625f;

inline void FillFrontendConfig(FrontendConfig* c) {
    FrontendFillConfigWithDefaults(c);
    c->window.size_ms = 30;
    c->window.step_size_ms = 10;

    c->filterbank.num_channels = kNumChannels;
    c->filterbank.lower_band_limit = 125.0f;
    c->filterbank.upper_band_limit = 7500.0f;

    c->noise_reduction.smoothing_bits = 10;
    c->noise_reduction.even_smoothing = 0.025f;
    c->noise_reduction.odd_smoothing = 0.06f;
    c->noise_reduction.min_signal_remaining = 0.05f;

    c->pcan_gain_control.enable_pcan = 1;
    c->pcan_gain_control.strength = 0.95f;
    c->pcan_gain_control.offset = 80.0f;
    c->pcan_gain_control.gain_bits = 21;

    c->log_scale.enable_log = 1;
    c->log_scale.scale_shift = 6;
}

}  // namespace wakeword
