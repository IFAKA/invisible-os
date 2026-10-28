# Makes the expected numbers of ClickModelTest from the real Nomon-Simulation code (clone github.com/nbonaker/Nomon-Simulation to /tmp/nomon-src/sim).
# Needs numpy; scipy is stubbed (shim/scipy/stats.py with norm=None). Run: python3 -I nomon_reference.py (from another dir).
import sys, json, math
sys.path.insert(0, "/tmp/shim_unused")
sys.path.insert(0, "/tmp/ref/shim")
sys.path.insert(0, "/tmp/nomon-src/sim")
import numpy as np
from Nomon_Core import config
from Nomon_Core.clock_inference_engine import ClockInference

class Sim: time=lambda s:0
class P:
    is_simulation=True
    def __init__(s, n, T, wd):
        s.clock_centers=[0]*n; s.words_on=list(range(n)); s.win_diffs=wd; s.time_rotate=T
        s.sim_time=Sim(); s.pretrain_window=False
class BC:
    def __init__(s,p): s.parent=p
T=3.0
keys="ABCDE"
noon=[0.0,0.6,1.2,1.8,2.4]
prior=[0.4,0.25,0.15,0.1,0.1]
n=5
p=P(n,T,[config.win_diff_base]*n)
ci=ClockInference(p,BC(p))
ci.clock_history=[[]]; ci.win_history=[-1]
def start():
    ci.clock_util.num_divs_time  # exists
    for i in range(n):
        ci.clock_util.cur_hours[i]=((1.5-noon[i])%T)/ (T/ci.clock_util.num_divs_time)
        ci.cscores[i]=math.log(prior[i])
    ci.update_sorted_inds()
    ci.handicap_cscores(False, True)
def post():
    m=max(ci.cscores); e=[math.exp(c-m) for c in ci.cscores]; s=sum(e); return [x/s for x in e]
rounds=[[1300,4300],[100],[1750,2100,5100],[2650],[1900],[1300],[1450],[200],[1350],[1800,4800],[1400],[2600]]
out=[]
for r in rounds:
    start()
    for c in r:
        d=c/1000.0
        ci.update_scores(d)
        ci.update_history(d)
        w=ci.is_winner()
        out.append({"post":post(),"win":bool(w),"best":ci.sorted_inds[0]})
        if w:
            ci.win_history[0]=ci.sorted_inds[0]
            ci.learn_scores(False)
            break
    else:
        pass
    out.append({"round_end":True,"ksigma":ci.kde.ksigma,"Z":ci.kde.Z})
print(json.dumps({"n_ksigma":ci.kde.n_ksigma,"n_hist":ci.n_hist,"ev":out},indent=0))
