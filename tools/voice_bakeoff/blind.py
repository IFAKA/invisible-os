import json, random, numpy as np, soundfile as sf, os
from master import master
random.seed(7)
def build(cands, idxs, name):
    labels = list("ABCDEFGH")[:len(cands)]; random.shuffle(cands); key = {}
    os.makedirs("out/blind", exist_ok=True)
    for lab, c in zip(labels, cands):
        parts = []
        for i in idxs:
            a, sr = sf.read(f"out/{c}/{i:02d}.wav"); a = master(a, sr)
            if sr != 24000: a = np.interp(np.linspace(0, len(a)-1, int(len(a)*24000/sr)), np.arange(len(a)), a)
            parts += [a, np.zeros(int(.9*24000))]
        sf.write(f"out/blind/{name}_{lab}.wav", np.concatenate(parts), 24000); key[lab] = c
    json.dump(key, open(f"out/blind/{name}_KEY.json", "w"))
    print(name, "->", " ".join(f"{name}_{l}.wav" for l in labels))
build(["kokoro_af_heart","kokoro_af_bella","kokoro_bf_emma","kokoro_am_michael","ming_woman","ming_man"], [0,1,4,6,7], "EN")
build(["kokoro_ef_dora","kokoro_em_alex","ming_es_woman"], [0,1,2,3], "ES")
