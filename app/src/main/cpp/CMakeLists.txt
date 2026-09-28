cmake_minimum_required(VERSION 3.22.1)
project(microfrontend_jni C CXX)

# Same source set + flags as pymicro-features' setup.py (the extractor the model was
# trained against). kiss_fft_int16.cc #includes kissfft's kiss_fft.cc / kiss_fftr.cc
# itself inside a fixed-point namespace, so they are NOT compiled separately here.
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party/pymicro-features)
set(LIB ${TP}/tensorflow/lite/experimental/microfrontend/lib)

add_library(microfrontend_jni SHARED
    frontend_jni.cc
    ${LIB}/kiss_fft_int16.cc
    ${LIB}/fft.cc
    ${LIB}/fft_util.cc
    ${LIB}/filterbank.cc
    ${LIB}/filterbank_util.cc
    ${LIB}/frontend.cc
    ${LIB}/frontend_util.cc
    ${LIB}/log_lut.cc
    ${LIB}/log_scale.cc
    ${LIB}/log_scale_util.cc
    ${LIB}/noise_reduction.cc
    ${LIB}/noise_reduction_util.cc
    ${LIB}/pcan_gain_control.cc
    ${LIB}/pcan_gain_control_util.cc
    ${LIB}/window.cc
    ${LIB}/window_util.cc
)

target_include_directories(microfrontend_jni PRIVATE ${TP} ${TP}/kissfft)
target_compile_definitions(microfrontend_jni PRIVATE FIXED_POINT=16)
target_link_libraries(microfrontend_jni log m)
