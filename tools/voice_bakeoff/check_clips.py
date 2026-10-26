import sys, re, numpy as np, soundfile as sf, jiwer
from faster_whisper import WhisperModel
from manifest import all_items, key
w = WhisperModel("small", device="cpu", compute_type="int8"); norm = lambda s: re.sub(r"[^\w\s]", "", s.lower()).strip()
import os
for l, t in all_items():
    f = f"{sys.argv[1]}/{key(l, t)}.wav"
    if not os.path.exists(f): continue
    a, sr = sf.read(f); x = np.interp(np.linspace(0, len(a)-1, int(len(a)*16000/sr)), np.arange(len(a)), a).astype("float32")
    h = " ".join(s.text for s in w.transcribe(x, language=l)[0]); print(f"{jiwer.wer(norm(t), norm(h))*100:4.0f}%  {t!r}  ->  {h.strip()!r}")
