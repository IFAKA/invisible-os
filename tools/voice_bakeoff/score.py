import sys, glob, os, re, numpy as np, soundfile as sf, pyloudnorm as pyln, jiwer
from faster_whisper import WhisperModel
w = WhisperModel("small", device="cpu", compute_type="int8")
norm = lambda s: re.sub(r"[^\w\s]", "", s.lower()).strip()
def lead(a, sr, thr=0.01):
    idx = np.where(np.abs(a) > thr)[0]; return (idx[0]/sr*1000, (len(a)-idx[-1])/sr*1000) if len(idx) else (0, 0)
for d in sorted(glob.glob("out/*/")):
    name = os.path.basename(d.rstrip("/")); es = "es" in name.split("_")[1:2] or name.endswith(("dora", "alex")) or "es_" in name
    lines = open("sentences_es.txt" if es else "sentences.txt").read().strip().split("\n")
    L, TP, LS, TS, refs, hyps = [], [], [], [], [], []
    for i, line in enumerate(lines):
        f = f"{d}{i:02d}.wav"
        if not os.path.exists(f): continue
        a, sr = sf.read(f); a = a if a.ndim == 1 else a.mean(1)
        L.append(pyln.Meter(sr).integrated_loudness(a)); TP.append(20*np.log10(np.abs(a).max()+1e-9))
        l, t = lead(a, sr); LS.append(l); TS.append(t)
        x16 = np.interp(np.linspace(0, len(a)-1, int(len(a)*16000/sr)), np.arange(len(a)), a).astype('float32')
        segs, _ = w.transcribe(x16, language="es" if es else "en"); refs.append(norm(line)); hyps.append(norm(" ".join(s.text for s in segs)))
    print(f"{name:22s} LUFS {np.mean(L):6.1f} (spread {np.ptp(L):4.1f})  peak {max(TP):5.1f} dB  lead {np.mean(LS):4.0f} ms  tail {np.mean(TS):4.0f} ms  WER {jiwer.wer(refs,hyps)*100:4.1f}%")
