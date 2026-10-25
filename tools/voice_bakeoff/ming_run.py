import sys, os, time, torch
sys.path.insert(0, "ming")
from ming_tts import MingTTS
dev = sys.argv[1]; style = sys.argv[2]; tag = sys.argv[3]
m = MingTTS("models/Ming-3.0-tts-0.5b", device=dev, text_normalization=False)
os.makedirs(f"out/ming_{tag}", exist_ok=True)
for i, line in enumerate(open(os.environ.get("SENT","sentences.txt")).read().strip().split("\n")):
    t = time.time()
    m.synthesize(task=os.environ.get("TASK","VOICE_DESIGN"), text=line, language_id=os.environ.get("LANG_ID") or None, controls={"style": style}, use_zero_spk_emb=True, output_path=f"out/ming_{tag}/{i:02d}.wav")
    print("ming", tag, i, f"{time.time()-t:.1f}s")
