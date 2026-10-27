import json,glob,os,datetime,sys,re
out=open('/tmp/wf/pairs.jsonl','w'); meta=open('/tmp/wf/meta.jsonl','w')
def ts(t): return datetime.datetime.fromisoformat(t.replace('Z','+00:00')).timestamp()
for f in glob.glob(os.path.expanduser('~/.claude/projects/*/*.jsonl')):
    proj=f.split('/')[-2].split('-')[-1]; sid=os.path.basename(f)[:8]
    last_txt=None; last_t=None; prompt_t=None; side=('subagents' in f)
    try: fh=open(f,errors='ignore')
    except: continue
    for l in fh:
        try:d=json.loads(l)
        except:continue
        if d.get('isSidechain'): continue
        t=d.get('timestamp'); m=d.get('message') or {}
        if not t or not isinstance(m,dict): continue
        t=ts(t); c=m.get('content')
        if d.get('type')=='assistant' and isinstance(c,list):
            for x in c:
                if not isinstance(x,dict):continue
                if x.get('type')=='text' and x.get('text','').strip(): last_txt=x['text']; last_t=t
                if x.get('type')=='tool_use':
                    meta.write(json.dumps({'k':'tool','n':x.get('name'),'p':proj,'t':t})+'\n')
                    if x.get('name')=='AskUserQuestion': meta.write(json.dumps({'k':'ask','q':x.get('input'),'p':proj,'t':t})+'\n')
        elif d.get('type')=='user':
            if isinstance(c,list):
                for x in c:
                    if isinstance(x,dict) and x.get('type')=='tool_result':
                        r=x.get('content'); r=r if isinstance(r,str) else json.dumps(r)[:600]
                        if "doesn't want to proceed" in r or 'rejected' in r[:200] or 'User declined' in r: meta.write(json.dumps({'k':'deny','r':r[:300],'p':proj,'t':t})+'\n')
                        if 'User has answered' in r or 'answered your questions' in r: meta.write(json.dumps({'k':'answer','r':r[:400],'p':proj,'t':t})+'\n')
                txt=' '.join(x.get('text','') for x in c if isinstance(x,dict) and x.get('type')=='text')
                img=sum(1 for x in c if isinstance(x,dict) and x.get('type')=='image')
            else: txt=c if isinstance(c,str) else ''; img=0
            if not txt.strip() or txt.startswith('<') or d.get('isMeta') or 'This session is being continued' in txt[:80]: continue
            out.write(json.dumps({'p':proj,'s':sid,'t':t,'prev':(last_txt or '')[-500:],'gap':(t-last_t) if last_t else None,'work':(last_t-prompt_t) if (last_t and prompt_t and last_t>prompt_t) else None,'u':txt[:1500],'img':img,'perm':d.get('permissionMode')})+'\n')
            prompt_t=t; last_txt=None; last_t=None
