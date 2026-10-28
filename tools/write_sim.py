#!/usr/bin/env python3
"""Offline cost model of the Write keyboard: seconds to type words under 3 designs.
Word frequency = vocab.txt rank (Zipf). Step costs measured from write_usage.tsv (next 2.9 s, pick 3.1 s, menu trip 9.4 s)."""
import sys
V=[w for w in open('app/programs/write/src/main/assets/lm/vocab.txt').read().split('\n')[3:] if w and w.isalpha()]
NEXT,PICK,MENU=2.9,3.1,9.4
BYUSE="eaosrnidltcumpbgvyqhfzjxkw"
def freq(vocab): return {w:1/(i+10) for i,w in enumerate(vocab)}
def pair_cost(rank): return (rank//2)*NEXT+PICK
def letters_rank(f,prefix,c,normalized):
    if not normalized: order=BYUSE  # today: the 1e-6 tie-break prior beats the tiny word mass
    else:
        m={}
        for w,p in f.items():
            if len(w)>len(prefix) and w.startswith(prefix): m[w[len(prefix)]]=m.get(w[len(prefix)],0)+p
        order=sorted(BYUSE,key=lambda x:-(m.get(x,0)/(sum(m.values()) or 1)+1e-9*(26-BYUSE.index(x))))
    return order.index(c)
def cost_A(word,f): # today: spell every letter, then menu Space
    return sum(pair_cost(letters_rank(f,word[:i],word[i],False)) for i in range(len(word)))+MENU
def cost_B(word,f): # letter order fixed, still menu Space
    return sum(pair_cost(letters_rank(f,word[:i],word[i],True)) for i in range(len(word)))+MENU
def cost_C(word,f): # one stream: items = whole words + letters, ranked by chance; a word pick adds the space
    t=0;prefix=''
    while True:
        ws=sorted([(p,w) for w,p in f.items() if w.startswith(prefix) and len(w)>len(prefix)],reverse=True)
        tot=sum(p for p,_ in ws) + (f.get(prefix,0))
        items=[]
        if prefix and prefix in f: items.append((f[prefix]/tot,'WORD',prefix))
        for p,w in ws[:6]: items.append((p/tot,'WORD',w))
        m={}
        for p,w in ws: m[w[len(prefix)]]=m.get(w[len(prefix)],0)+p/tot
        for c in BYUSE: items.append((m.get(c,0)+1e-9*(26-BYUSE.index(c)),'L',c))
        # words already covered by a letter keep their own slot; dedupe
        items.sort(key=lambda x:-x[0])
        for k,(p,kind,x) in enumerate(items):
            if kind=='WORD' and x==word: return t+pair_cost(k)
            if kind=='L' and prefix+x==word[:len(prefix)+1]:
                t+=pair_cost(k); prefix+=x; break
        if prefix==word and word not in f: return t+MENU*0+PICK  # unknown word: an "end word" item, about one pick
words=sys.argv[1:] or "android paranoid billie jean hailie song i will be home in ten minutes".split()
f=freq(V)
tA=tB=tC=tC2=0
print("word        today   fixed   stream  stream(2nd time, learned)")
for w in words:
    a,b,c=cost_A(w,f),cost_B(w,f),cost_C(w,f)
    f2=dict(f); f2[w]=max(f2.get(w,0),1/(10+200))   # spelled once: kept as a word of the owner
    c2=cost_C(w,f2)
    tA+=a;tB+=b;tC+=c;tC2+=c2
    print(f"{w:10} {a:6.0f}s {b:6.0f}s {c:6.0f}s {c2:6.0f}s")
n=sum(len(w)+1 for w in words)
print(f"TOTAL     {tA:6.0f}s {tB:6.0f}s {tC:6.0f}s {tC2:6.0f}s   ({n} chars: {tA/n:.1f} / {tB/n:.1f} / {tC/n:.1f} / {tC2/n:.1f} s per char)")
