import json,collections,re
P=[json.loads(l) for l in open('/tmp/wf/pairs.jsonl')]
n=lambda t: re.sub(r'\s+',' ',re.sub(r'[^a-z0-9 ]',' ',t.lower())).strip()
FIX=['go','yes','ok','commit','push','do it','continue','compact','done','no','both','all','eh','wdym','love it','love it commit','commit and push','push changes','commit all','go ahead','ok do it','do all','still']
FIXN=set(FIX)|{'what do you recommend','what do yo recomend','i dont get it','do what you recommend','do what you recomend','so we are done','can i close this chat','we are done'}
tot=len(P); hit_fix=0; has_opts=0; hit_opt=0; hit_any=0; stat=0
for r in P:
    u=n(r['u']); m=re.search(r'needs (me|you):?\**\s*(.*)',r['prev'],re.I)
    opts=set()
    if m:
        stat+=1
        opts={n(x) for x in re.findall(r'["“]([^"”]{1,40})["”]',m.group(2))}
        if opts: has_opts+=1
    f=u in FIXN; o=u in opts or any(u.startswith(x) for x in opts if x)
    hit_fix+=f; hit_opt+=o; hit_any+= (f or o)
print(f"replies {tot}; fixed list covers {hit_fix} ({hit_fix/tot:.0%}); needs-me quoted options: present in {has_opts}/{stat} status blocks, matched {hit_opt}; either {hit_any} ({hit_any/tot:.0%})")
inter=sum('interrupted by user' in r['u'] for r in P); print("interrupts",inter, f"{inter/tot:.0%}")
img=sum(r['img']>0 or '[Image' in r['u'] for r in P); print("with screenshot",img,f"{img/tot:.0%}")
