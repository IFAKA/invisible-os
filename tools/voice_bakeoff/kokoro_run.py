import sys, time, os, soundfile as sf
from kokoro_onnx import Kokoro
k = Kokoro("models/kokoro-v1.0.onnx", "models/voices-v1.0.bin")
lang = sys.argv[1]; sent = sys.argv[2]; voices = sys.argv[3:]
lines = open(sent).read().strip().split('\n')
for v in voices:
    out = f"out/kokoro_{v}"; os.makedirs(out, exist_ok=True)
    for i, line in enumerate(lines):
        t = time.time()
        a, sr = k.create(line, voice=v, speed=0.95, lang=lang)
        sf.write(f"{out}/{i:02d}.wav", a, sr)
        print(v, i, f"{time.time()-t:.2f}s for {len(a)/sr:.2f}s audio")
