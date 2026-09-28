# Wake-word test app (Android)

Standalone test harness for `hey_find_my_phone.tflite`, implementing
`../WAKE_WORD_INTEGRATION.md` end to end. It is **not** the production app: it exists
to validate the model on real phones before the app team integrates it.

Pipeline: `AudioRecord` (16 kHz mono, VOICE_RECOGNITION source) → TFLite Micro
frontend (native, vendored from rhasspy/pymicro-features, same sources + flags as
its `setup.py`) → 3-frame non-overlapping blocks → LiteRT `Interpreter` → decision
rule → alarm-stream ring at max volume + vibrate for up to 30 s.

| file | role | doc section |
| --- | --- | --- |
| `app/src/main/cpp/frontend_jni.cc` | frontend config (14 fields), uint16 read, ×0.0390625 | 2 |
| `WakeWordEngine.kt` | sample buffering, feature_queue, quantize, inference | 2–3 |
| `DecisionRules.kt` | streak / moving average / K-of-M, all tunable live from the app | 4 |
| `TriggerConfig.kt` | knobs, presets (sweep best, sweep 2nd, loose), models, mic sources | 4 |
| `WakeWordService.kt` | foreground mic service, wake lock, notifications | 1 |
| `Ringer.kt` | stand-in for the app's `RingtoneHelper` | 0 |

## Build

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (signed with the local debug
key: installable for testing, not for Play). Models: v2, v3 (= `hey_find_my_phone.tflite`)
and v4 ship in `app/src/main/assets/`, picked in the app. Add one by dropping it there and
listing it in `MODELS` (`TriggerConfig.kt`).

## Parity check (APK preprocessing vs the Python reference)

`tools/parity/` builds the app's frontend (same vendored sources, same
`wakeword_frontend_config.h`) as a host DLL, then compares it against
`pymicro_features` on every wav in `test/recordings/`: features, int8 model inputs and
scores, for every bundled model. Needs a C++ compiler (`pip install ziglang` works):

```
CXX="python -m ziglang c++ -target x86_64-windows-gnu" sh tools/parity/build.sh
py -3.13 tools/parity/parity_check.py
```

Last run: all 25 recordings bit-identical. The one expected difference: offline
`score_wav.py` drops a file's final 10 ms chunk (`idx + 320 < len`); the app, like
`live_mic.py`, doesn't. That only matters at end-of-file, never on a live stream.
