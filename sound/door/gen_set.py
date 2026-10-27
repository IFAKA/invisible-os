"""The whole OS sound set in the XMB (PS3) mood. Run: /tmp/sdv/bin/python sound/door/gen_set.py -> sound/door/set/<tone>.wav
Rules: soft glass bells (FM, slow attack), deep pad swells, long quiet reverb. Pitch still means direction (up = in/yes, down = out/no).
Fast list sounds (tap, tick) stay tiny and dry. Levels (peak dBFS) are the ones in Earcons.kt LEVEL."""
from lib import *
import lib
OUT = os.path.join(OUT, 'set'); lib.OUT = OUT
C4,G4,A4 = 261.63,392.0,440.0
def seq(notes, step, tau=.25, g=1.0, idx=1.4, dur=1.0, fall=0.0):
    b = buf(len(notes)*step + dur)
    for i, f in enumerate(notes): place(b, bell(f, dur, tau, idx=idx)*(g*(1-fall*i)), i*step)
    return b
def blip(f, ms=70, tau=.03): return sine(f, ms/1000, tau, a=.004)
def pad(freqs, dur, up=.4, tau=.3, lo=300, hi=3500, closing=False):
    t = T(dur); y = sum(np.sin(2*np.pi*f*t) + .6*np.sin(2*np.pi*f*1.003*t + 1) for f in freqs)
    if closing: return lp_sweep(y*np.minimum(1, t/.03)*np.exp(-t/tau), hi, lo)
    return lp_sweep(y*np.minimum(1, t/up)**2*np.exp(-np.maximum(0, t-up)/tau), lo, hi)
def mix(*parts):
    n = max(len(p) for p, _ in parts); b = np.zeros(n)
    for p, at in parts: place(b, p, at)
    return b
S = {}   # name -> (mono, peak_db, reverb_s, wet)
# --- list sounds: tiny, dry, glassy
S['tap']  = (blip(2400, 25, .006), -26, .2, 0)
S['tick'] = (blip(1700, 35, .008) + .4*blip(420, 35, .01), -24, .2, 0)
for i, (n, f) in enumerate(zip(range(1, 6), [G4, 523.25, 659.25, G5, C6])): S[f'lvl{n}'] = (blip(f, 90, .035), -22, .5, .12)
# --- a new screen: one quiet bell over a soft breath
page = mix((bell(G5, .6, .2, idx=1.0)*.8, 0), (pad([G4, 587.33], .5, .12, .15, 400, 2500), 0))
for k in ['message', 'timer', 'more', 'volume']: S[k] = (page, -20, 1.0, .3)
# --- answers to clicks
S['pick']      = (seq([523.25, 659.25], .06, .2, dur=.5), -17, .8, .28)
S['again']     = (seq([523.25, 659.25], .05, .15, .8, dur=.35), -18, .7, .25)
S['step_back'] = (seq([659.25, 523.25], .05, .15, .8, dur=.35), -18, .7, .25)
S['back']      = (seq([G5, 659.25, 523.25], .07, .2, dur=.5, fall=.12), -17, .9, .3)
S['restart']   = (seq([G4, 523.25, 659.25], .075, .2, dur=.5), -17, .9, .3)
S['undo']      = (seq([523.25, 659.25, G5], .08, .15, .6, dur=.4), -22, .9, .3)
# --- results
S['sent']   = (mix((seq([523.25, G5, C6], .1, .45, dur=1.3), 0), (pad([261.63, 392, 659.25], 1.2, .25, .5, 400, 4500), 0)), -13, 1.6, .38)
S['cancel'] = (seq([G5, 523.25], .15, .3, dur=.7, idx=1.0), -17, .9, .3)
S['unknown']= (mix((sine(G4/2, .35, .12, a=.01), 0), (sine(G4, .3, .08, a=.01)*.3, 0)), -16, .5, .12)   # low and soft: no
S['ding']   = (seq([659.25, 523.25], .2, .25, .7, dur=.7), -22, 1.0, .3)
S['arrive'] = (bell(659.25, .6, .22, idx=1.1), -20, .9, .3)
S['arrive_fav'] = (seq([523.25, 659.25, G5], .09, .2, dur=.45), -17, .9, .3)
S['claude'] = (mix((seq([G4, 523.25, G5, C6], .085, .3, dur=.8, idx=1.6), 0), (pad([261.63, 392], .6, .15, .3, 500, 4000), 0)), -15, 1.0, .3)
al = buf(2.4)
place(al, seq([523.25, 659.25, G5, C6, G5, C6], .12, .35, dur=.7, idx=1.8), 0)
S['alarm']  = (al, -10, 1.0, .25)
S['wait']   = (mix((sine(C5 if False else 523.25, .22, .06, a=.02), 0), (sine(523.25, .25, .07, a=.02)*.7, .15)), -27, .6, .2)
# --- the doors (chosen: option 6)
# enter: speech starts 400 ms after the tone (toneGapMs), so the swell peaks at 250 ms and is quiet by 500 ms; only a soft tail runs under the first word
t = T(.9); p = xmb_pad(.9)
e = np.minimum(1, t/.25)**2*np.exp(-np.maximum(0, t-.25)/.09)
b = lp_sweep(p*e, 300, 4500) + .2*bp_noise(.9, 1500, 6000, q=1.5)*np.minimum(1, t/.25)**2*np.exp(-np.maximum(0, t-.25)/.07)
place(b, bell(G5, .6, .16, idx=1.2)*.8, .2); S['hub'] = (b, -14, .7, .22)
t = T(1.2); p = xmb_pad(1.2); e = np.minimum(1, t/.03)*np.exp(-t/.4)
b = lp_sweep(p*e, 4000, 200); b = np.pad(b, (0, int(.3*SR))); place(b, bell(523.25, 1.0, .3, idx=1.0)*.7, 0)
S['leave'] = S['exit'] = (b, -18, 1.6, .4)
if __name__ == '__main__':
    import sys
    os.makedirs(OUT, exist_ok=True)
    for k, (m, db, rt, wet) in S.items(): save(k, m, db, rt, wet)
    if len(sys.argv) > 1:  # ship: mono 16-bit copies the app loads (Earcons.pcm)
        os.makedirs(sys.argv[1], exist_ok=True)
        for k in S:
            with wave.open(os.path.join(OUT, k + '.wav')) as r: d = np.frombuffer(r.readframes(r.getnframes()), '<i2').reshape(-1, 2).astype(float)
            mono = (d.mean(1) / np.abs(d.mean(1)).max() * np.abs(d).max()).astype('<i2')
            with wave.open(os.path.join(sys.argv[1], k + '.wav'), 'wb') as w: w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(mono.tobytes())
    print(len(S), 'sounds')
