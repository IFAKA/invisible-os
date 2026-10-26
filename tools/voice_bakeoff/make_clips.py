"""Make the voice clips: one reference voice (ref/ref_en.wav), every sentence in manifest.py, mastered, written as 24 kHz mono WAV named by key.
Only missing clips are made. Usage: make_clips.py OUTDIR [shard n_shards] [--limit K]"""
import sys, os, time, torch, torchaudio, numpy as np, soundfile as sf
sys.path.insert(0, "ming")
from manifest import all_items, key
from master import master
out = sys.argv[1]; shard, n = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) > 3 and sys.argv[2].isdigit() else (0, 1)
limit = int(sys.argv[sys.argv.index("--limit") + 1]) if "--limit" in sys.argv else None
REF_TEXT = "Click when you hear it. Two clicks: one back. Three clicks: go back."
os.makedirs(out, exist_ok=True)
ONLY = sys.argv[sys.argv.index("--lang") + 1] if "--lang" in sys.argv else None
todo = [(l, t) for i, (l, t) in enumerate(all_items()) if (ONLY is None or l == ONLY) and i % n == shard and not os.path.exists(f"{out}/{key(l, t)}.wav")][:limit]
print(f"shard {shard}/{n}: {len(todo)} to make", flush=True)
from ming_tts import MingTTS
import re, jiwer, random, json
def status(**kw): json.dump(dict(kw, shard=shard, t=time.time()), open(f"/tmp/clip_status_{shard}.json", "w"))
torch.set_num_threads(4)
from faster_whisper import WhisperModel
asr = WhisperModel("small", device="cpu", compute_type="int8")
UN = {w: i for i, w in enumerate("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split())}
TENS = {w: 10 * (i + 2) for i, w in enumerate("twenty thirty forty fifty sixty seventy eighty ninety".split())}
def norm(s):
    """Lowercase words and punctuation off; spelled numbers ('eighty four') become digits ('84'); spaces removed, so 'clean up' = 'cleanup'."""
    toks = re.sub(r"[^\w\s]", " ", s.lower().replace("=", " equals ").replace("%", " percent ")).split(); out = []
    for w in toks:
        if w == "hundred" and out and out[-1].isdigit(): out[-1] = str(int(out[-1]) * 100)
        elif w in UN: out.append(str(UN[w]))
        elif w in TENS: out.append(str(TENS[w]))
        elif w in UN and out and out[-1] in map(str, TENS.values()): out[-1] = str(int(out[-1]) + UN[w])
        else: out.append(w)
    merged = []
    for w in out:
        if merged and w.isdigit() and 1 <= int(w) <= 9 and merged[-1].isdigit() and int(merged[-1]) in TENS.values(): merged[-1] = str(int(merged[-1]) + int(w))
        else: merged.append(w)
    return "".join(merged)
import inflect
_inf = inflect.engine()
def spoken(t): return re.sub(r"\d+", lambda x: _inf.number_to_words(int(x.group()), andword=""), t)   # the voice gets "seven", the app still looks up "7"
m = MingTTS("models/Ming-3.0-tts-0.5b", device="cpu", text_normalization=False)
REF = {"en": ("ref/ref_en.wav", REF_TEXT, "TTS", "English"), "es": ("ref/ref_es.wav", "Temporizador de 5 minutos", "MULTILINGUAL", "Spanish")}
for l, t in todo:
    t0 = time.time(); tmp = f"/tmp/ming_{shard}.wav"; best = None
    for attempt in range(4):
        status(state="making", lang=l, text=t, attempt=attempt + 1, started=t0, best=None if best is None else best[0], left=len(todo) - todo.index((l, t)))
        wav, ptxt, task, lid = REF[l]
        try:
            m.synthesize(task=task, text=spoken(t), language_id=lid, prompt_wav_path=wav, prompt_text=ptxt, use_spk_emb=True, output_path=tmp, temperature=0.0 if attempt == 0 else 0.4)
            a, sr = sf.read(tmp); a = a if a.ndim == 1 else a.mean(1)
            a = torchaudio.functional.resample(torch.tensor(a, dtype=torch.float32), sr, 24000).numpy() if sr != 24000 else a
            a = master(a, 24000)
            x = np.interp(np.linspace(0, len(a)-1, int(len(a)*16000/24000)), np.arange(len(a)), a).astype("float32")
            status(state="checking", lang=l, text=t, attempt=attempt + 1, started=t0, best=None if best is None else best[0], left=len(todo) - todo.index((l, t)))
            h = " ".join(s.text for s in asr.transcribe(x, language=l)[0]); wer = jiwer.cer(norm(t), norm(h)) if norm(h) else 1.0
        except Exception as e:
            print("  try failed:", repr(e)[:80], flush=True); continue
        if best is None or wer < best[0]: best = (wer, a, h)
        if wer <= 0.08: break
    if best is None: print(f"FAIL {l} {t!r}", flush=True); continue
    sf.write(f"{out}/{key(l, t)}.wav", best[1], 24000, subtype="PCM_16")
    status(state="saved", lang=l, text=t, attempt=attempt + 1, started=t0, best=best[0], left=len(todo) - todo.index((l, t)), heard=best[2].strip())
    print(f"{l} wer={best[0]*100:3.0f}% tries={attempt+1} {t[:44]!r} -> {best[2].strip()[:44]!r} {time.time()-t0:.1f}s", flush=True)
