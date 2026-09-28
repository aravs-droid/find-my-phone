#!/bin/sh
# Builds wakeword_frontend.dll from the APK's vendored frontend sources.
# Needs a C++ compiler; `pip install ziglang` and CXX="python -m ziglang c++" works.
set -e
cd "$(dirname "$0")"
CPP=../../app/src/main/cpp
TP=$CPP/third_party/pymicro-features
L=$TP/tensorflow/lite/experimental/microfrontend/lib
${CXX:-c++} -O2 -shared -DFIXED_POINT=16 -I"$TP" -I"$TP/kissfft" -I"$CPP" -o wakeword_frontend.dll parity_shim.cc \
  $L/kiss_fft_int16.cc $L/fft.cc $L/fft_util.cc $L/filterbank.cc $L/filterbank_util.cc \
  $L/frontend.cc $L/frontend_util.cc $L/log_lut.cc $L/log_scale.cc $L/log_scale_util.cc \
  $L/noise_reduction.cc $L/noise_reduction_util.cc $L/pcan_gain_control.cc \
  $L/pcan_gain_control_util.cc $L/window.cc $L/window_util.cc
echo built wakeword_frontend.dll
