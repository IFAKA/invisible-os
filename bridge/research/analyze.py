import json,collections,re
P=[json.loads(l) for l in open('/tmp/wf/pairs.jsonl')]; P.sort(key=lambda r:r['t'])
M=[json.loads(l) for l in open('/tmp/wf/meta.jsonl')]
def n(t): return re.sub(r'\s+',' ',re.sub(r'[^a-z0-9 ]',' ',re.sub(r'\[(image|pasted)[^\]]*\]','',t.lower()))).strip()
print("pairs",len(P),"perm modes",collections.Counter(r['perm'] for r in P).most_common())
k=collections.Counter(m['k'] for m in M); print("meta",dict(k)); 
print("top tools",collections.Counter(m['n'] for m in M if m['k']=='tool').most_common(8))
# Claude's last line type before reply
def tail(p):
    lines=[x for x in p.strip().split('\n') if x.strip()]; return (lines[-1] if lines else '').lower()
def kind(p):
    t=tail(p); pl=p.lower()
    if 'what needs me' in pl or 'needs me:' in pl or 'needs you:' in pl: 
        if re.search(r'needs (me|you): *(nothing|none)',pl): return 'status:nothing-needed'
        return 'status:asks-something'
    if t.endswith('?'): return 'question'
    if re.search(r'\b(say|reply|type) "?go',pl) or 'want me to' in pl or 'should i' in pl or 'shall i' in pl: return 'offer'
    if not p.strip(): return 'none(tool-only/interrupt)'
    return 'statement'
short=lambda u: len(n(u).split())<=3
c=collections.Counter(); cs=collections.defaultdict(collections.Counter)
for r in P:
    kd=kind(r['prev']); c[kd]+=1
    cs[kd]['short' if short(r['u']) else ('long' if len(n(r['u']).split())>15 else 'mid')]+=1
print("\nclaude ended with -> your reply length")
for kd,v in c.most_common(): print(f"  {kd:28} {v:5}  {dict(cs[kd])}")
# what short replies follow
sr=collections.defaultdict(collections.Counter)
for r in P:
    if short(r['u']) and n(r['u']): sr[kind(r['prev'])][n(r['u'])]+=1
for kd in sr: print(" ",kd,sr[kd].most_common(8))
# long prompt themes
def theme(r):
    u=n(r['u']); w=u.split()
    if r['img'] or '[image' in r['u'].lower(): return 'with screenshot'
    if re.search(r'\b(eh|wdym|i dont get|dont understand|explain|what do you mean|simpler)\b',u): return 'explain again'
    if re.search(r'\b(bug|error|crash|broken|doesnt work|not work|fails|wrong|still)\b',u): return 'report problem'
    if re.search(r'\b(dont make changes|hold on|wait|stop)\b',u): return 'hold/stop'
    if w and w[0] in('what','how','why','which','where','did','is','are','can','does','do') : return 'question'
    if re.search(r'\b(i want|we need|i need|make it|add|build|lets|let s|create|design)\b',u): return 'new ask/idea'
    if re.search(r'\b(go|yes|do it|commit|push|ok|love it)\b',u): return 'approve+extra'
    return 'other'
th=collections.Counter(theme(r) for r in P if len(n(r['u']).split())>3)
print("\nprompts >3 words by theme:",th.most_common())
import statistics as S
w=sorted(r['work'] for r in P if r['work']); q=lambda a,x:round(a[int(len(a)*x)])
print("claude work per prompt s p25/50/75/90:",[q(w,x) for x in(.25,.5,.75,.9)])
g=sorted(r['gap'] for r in P if r['gap'] and r['gap']<6*3600); print("your reply wait s p25/50/75/90:",[q(g,x) for x in(.25,.5,.75,.9)])
# gap by kind of reply
for lab,f in [('short',lambda r:short(r['u'])),('long',lambda r:len(n(r['u']).split())>15)]:
    g=sorted(r['gap'] for r in P if r['gap'] and r['gap']<6*3600 and f(r)); print(f"  wait before {lab} reply p50:",q(g,.5))
# switching
sw=0;dw=[];cur=None;start=None
for r in P:
    if r['s']!=cur:
        if cur: sw+=1
        cur=r['s']
sw_day=collections.Counter()
import datetime
prev=None
for r in P:
    d=datetime.datetime.fromtimestamp(r['t']).date()
    if prev and prev['s']!=r['s'] and r['t']-prev['t']<1800: sw_day['switch']+=1
    elif prev and r['t']-prev['t']<1800: sw_day['stay']+=1
    prev=r
print("next prompt within 30min goes to another session vs same:",dict(sw_day))
print("asks examples:",[ (m['q'] or {}).get('questions',[{}])[0].get('question','')[:80] for m in M if m['k']=='ask'][:5])
print("deny samples:",[m['r'][:100] for m in M if m['k']=='deny'][:4])
