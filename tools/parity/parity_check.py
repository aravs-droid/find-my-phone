"""Parity check: the APK's frontend + Kotlin feed/quantize logic vs the Python reference.

Builds nothing itself; expects wakeword_frontend.dll (see build.sh). For every wav:
  A) reference  = pymicro_features, fed exactly like test/score_wav.py
  B) app        = the app's C frontend (same sources + config header as the APK), fed
                  exactly like WakeWordEngine.kt (160-sample reads, advance by
                  samples_read, 3-row non-overlapping blocks, half-even quantization)
Compares features bit-for-bit, then the int8 model inputs and scores for each model.

    py -3.13 parity_check.py [wav ...]      (default: test/recordings/**/*.wav)
"""
import ctypes, glob, os, sys, wave
import numpy as np
from ai_edge_litert.interpreter import Interpreter
from pymicro_features import MicroFrontend

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
ASSETS = os.path.join(ROOT, "android", "app", "src", "main", "assets")
sys.path.insert(0, os.path.join(ROOT, "test"))
from score_wav import read_wav_16k_mono  # same loader the reference scripts use

lib = ctypes.CDLL(os.path.join(HERE, "wakeword_frontend.dll"))
lib.wf_create.restype = ctypes.c_void_p
lib.wf_process.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16), ctypes.c_int,
                           ctypes.POINTER(ctypes.c_float), ctypes.POINTER(ctypes.c_int)]
lib.wf_destroy.argtypes = [ctypes.c_void_p]


def ref_features(a):
    fe, data, rows, idx = MicroFrontend(), a.tobytes(), [], 0
    while idx + 320 < len(data):
        r = fe.process_samples(data[idx: idx + 320])
        if r.samples_read <= 0: break
        idx += r.samples_read * 2
        if r.features: rows.append(r.features)
    return np.array(rows, dtype=np.float32)


def app_features(a):
    """WakeWordEngine.feed/drainAudio: AudioRecord hands 160-sample reads."""
    h = lib.wf_create()
    out, n = (ctypes.c_float * 40)(), ctypes.c_int()
    pending, rows = np.zeros(0, dtype=np.int16), []
    for off in range(0, len(a), 160):
        pending = np.concatenate([pending, a[off: off + 160]])
        start = 0
        while len(pending) - start >= 160:
            chunk = np.ascontiguousarray(pending[start: start + 160])
            read = lib.wf_process(h, chunk.ctypes.data_as(ctypes.POINTER(ctypes.c_int16)), 160, out, ctypes.byref(n))
            if read <= 0: break
            start += read
            if n.value: rows.append(np.frombuffer(out, dtype=np.float32, count=n.value).copy())
        pending = pending[start:]
    lib.wf_destroy(h)
    return np.array(rows, dtype=np.float32)


def scores(feats, model, rounding):
    it = Interpreter(model_path=model); it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    (s_in, z_in), (s_out, z_out) = i["quantization"], o["quantization"]
    qs, out = [], []
    for k in range(0, len(feats) - 2, 3):
        q = np.clip(rounding(feats[k:k + 3] / np.float32(s_in)) + z_in, -128, 127).astype(np.int8)
        qs.append(q)
        it.set_tensor(i["index"], q.reshape(1, 3, 40)); it.invoke()
        out.append((int(it.get_tensor(o["index"])[0][0]) - z_out) * s_out)
    return np.array(qs), np.array(out)


def main():
    wavs = sys.argv[1:] or sorted(glob.glob(os.path.join(ROOT, "test", "recordings", "**", "*.wav"), recursive=True))
    models = sorted(glob.glob(os.path.join(ASSETS, "*.tflite")))
    ok = True
    for w in wavs:
        a, _ = read_wav_16k_mono(w)
        fa, fb = ref_features(a), app_features(a)
        # score_wav.py's offline loop is `idx + 320 < len` (strict), so it skips the
        # file's final full chunk; the app, like live_mic.py (`len(buf) >= 320`), feeds
        # it. Streams have no end, so compare the frames both produced.
        n = min(len(fa), len(fb))
        fa, fb, extra = fa[:n], fb[:n], len(fb) - n
        feat_eq = np.array_equal(fa, fb) and len(fa) - n == 0 and extra <= 1
        line = f"{os.path.basename(w):34s} {n:4d} frames {'bit-identical' if feat_eq else 'DIFF'}"
        if not feat_eq:
            ok = False
            line += f" (max |d|={np.abs(fa - fb).max():.5f}, extra={extra})"
        for m in models:
            qa, sa = scores(fa, m, np.round)          # reference: np.round (half-even)
            qb, sb = scores(fb, m, np.rint)           # app: Kotlin round() (half-even)
            eq = len(sa) == len(sb) and np.array_equal(qa, qb) and np.array_equal(sa, sb)
            ok &= eq
            line += f" | {os.path.basename(m)[:2]} {sa.max() if len(sa) else 0:.3f}{'' if eq else ' DIFF'}"
        print(line)
    print("\nALL IDENTICAL" if ok else "\nMISMATCHES FOUND")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
