import json,re,random,urllib.request,math,time,sys
P=[json.loads(l) for l in open('/tmp/wf/pairs.jsonl')]
n=lambda t: re.sub(r'\s+',' ',re.sub(r'[^a-z0-9 ]',' ',t.lower())).strip()
S=[r for r in P if r['prev'].strip() and 4<=len(n(r['u']).split())<=40 and '[Image' not in r['u'] and 'interrupted' not in r['u']]
random.seed(7); S=random.sample(S,int(sys.argv[2]) if len(sys.argv)>2 else 40)
def post(path,b,to=180): return json.load(urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:11434"+path,data=json.dumps(b).encode()),timeout=to))
def emb(t): return post("/api/embed",{"model":"all-minilm","input":t})["embeddings"][0]
def cos(a,b): return sum(x*y for x,y in zip(a,b))/math.sqrt(sum(x*x for x in a)*sum(y*y for y in b))
FIX=["go","yes","commit","push","do it","continue","what do you recommend","i dont get it, explain simpler"]
FE=[emb(f) for f in FIX]
model=sys.argv[1]; R=[]; TH=float(sys.argv[3]) if len(sys.argv)>3 else 0.5; hits_m=hits_f=0; lat=[]
for r in S:
    p=("You are the user of an AI coding assistant. The user types short, casual, lowercase replies with typos. "
       "Here is the end of the assistant's last message:\n---\n"+r['prev'][-450:]+"\n---\n"
       "Write the 3 most likely different replies the user sends next, one per line, no numbering.")
    t=time.time(); out=post("/api/generate",{"model":model,"prompt":p,"stream":False,"keep_alive":"30m","options":{"num_predict":90,"temperature":0.3}})["response"]; lat.append(time.time()-t)
    c=[x.strip(' -*0123456789.') for x in out.split('\n') if x.strip()][:3]
    ue=emb(r['u'][:300]); bm=max([cos(ue,emb(x)) for x in c] or [0]); bf=max(cos(ue,e) for e in FE)
    hits_m+=bm>=TH; hits_f+=bf>=TH; R.append((bm,bf))
    if len(R)<=6: print(f'  TRUE: {r["u"][:90]!r}\n  DRAFTS: {c}\n  sim model {bm:.2f} fixed {bf:.2f}')
lat.sort(); import statistics; print("mean sim model",round(statistics.mean(a for a,b in R),2),"fixed",round(statistics.mean(b for a,b in R),2))
print(f"{model}: n={len(S)} model-3 match {hits_m/len(S):.0%}, fixed-8 match {hits_f/len(S):.0%}, latency p50 {lat[len(lat)//2]:.1f}s")
