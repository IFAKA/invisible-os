"""Master one TTS clip: trim silence (keep 8 ms lead, 120 ms tail), fade, level to a loudness target, true-peak safe."""
import numpy as np, soundfile as sf, pyloudnorm as pyln
TARGET_LUFS, PEAK_DB = -19.0, -1.5
def master(a, sr, target=TARGET_LUFS):
    a = a if a.ndim == 1 else a.mean(1)
    idx = np.where(np.abs(a) > 0.012)[0]
    a = a[max(0, idx[0] - int(.008*sr)): idx[-1] + int(.12*sr)]
    n = int(.004*sr); a = a.copy(); a[:n] *= np.linspace(0, 1, n); m = int(.06*sr); a[-m:] *= np.linspace(1, 0, m)
    g = target - pyln.Meter(sr).integrated_loudness(a); a = a * 10**(g/20)
    pk = 20*np.log10(np.abs(a).max()); 
    if pk > PEAK_DB: a *= 10**((PEAK_DB-pk)/20)
    return a
