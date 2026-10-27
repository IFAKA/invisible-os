"""Enter/leave sound ideas for the OS. Run: /tmp/sdv/bin/python sound/door/gen.py  (writes sound/door/*.wav, stereo 44.1k)."""
import numpy as np, wave, os
from scipy.signal import butter, sosfilt, fftconvolve
SR = 44100
rng = np.random.default_rng(7)
OUT = os.path.dirname(os.path.abspath(__file__))
def T(d): return np.arange(int(d * SR)) / SR
def adsr(n, a, tau):  # soft attack, exponential decay
    t = np.arange(n) / SR
    return np.minimum(1, t / a) ** 2 * np.exp(-t / tau)
def place(buf, x, at):
    i = int(at * SR); buf[i:i + len(x)] += x[:len(buf) - i]
def bell(f, dur, tau=.25, ratio=3.5, idx=2.5, a=.004):
    t = T(dur); e = adsr(len(t), a, tau)
    I = idx * np.exp(-t / (tau * .5))
    y = np.sin(2*np.pi*f*t + I*np.sin(2*np.pi*f*ratio*t)) + .25*np.sin(2*np.pi*f*2.0*t)*np.exp(-t/(tau*.6))
    return y * e
def sine(f, dur, tau, a=.01, det=0.0):
    t = T(dur); return (np.sin(2*np.pi*f*t) + np.sin(2*np.pi*f*(1+det)*t)) * .5 * adsr(len(t), a, tau)
def bp_noise(dur, f0, f1, q=3):
    n = int(dur*SR); x = rng.standard_normal(n); out = np.zeros(n); blk = 256
    for i in range(0, n, blk):
        f = f0 * (f1/f0) ** (i/n); w = [f/(1+1/q)/(SR/2), min(.98, f*(1+1/q)/(SR/2))]
        out[i:i+blk] = sosfilt(butter(2, w, 'band', output='sos'), x[i:i+blk])  # block-wise (clicks are hidden by the envelope)
    return out
def lp(x, fc): return sosfilt(butter(2, fc/(SR/2), 'low', output='sos'), x)
def lp_sweep(x, f0, f1):
    n = len(x); out = np.zeros(n); blk = 512
    for i in range(0, n, blk):
        f = f0 * (f1/f0) ** (i/n); out[i:i+blk] = sosfilt(butter(2, min(f, 20000)/(SR/2), 'low', output='sos'), x[i:i+blk])
    return out
def reverb(x, rt=.9, wet=.28):
    n = int(rt*SR); t = np.arange(n)/SR; env = np.exp(-6.9*t/rt)
    L = lp(rng.standard_normal(n)*env, 6000); R = lp(rng.standard_normal(n)*env, 6000)
    L /= np.abs(L).sum()/8; R /= np.abs(R).sum()/8
    pad = np.concatenate([x, np.zeros(n)])
    return np.stack([pad + wet*fftconvolve(pad, L)[:len(pad)], pad + wet*fftconvolve(pad, R)[:len(pad)]], 1)
def save(name, mono, peak_db=-12, rt=.9, wet=.28, pan=None):
    s = reverb(mono, rt, wet)
    if pan is not None: s = s * np.array([1-pan, 1+pan])
    s = s[:int(len(s) - 0)]
    nz = np.nonzero(np.abs(s).max(1) > 1e-3 * np.abs(s).max())[0][-1]; s = s[:nz+1]
    k = min(int(.05*SR), len(s)//3); s[-k:] *= np.linspace(1, 0, k)[:, None]
    m = int(1.55*SR)
    if len(s) > m:  # the kit allows 1.6 s per tone: cut the quiet tail with a fade
        s = s[:m]; f = int(.2*SR); s[-f:] *= np.linspace(1, 0, f)[:, None]
    s = s / np.abs(s).max() * 10**(peak_db/20)
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((s*32767).astype('<i2').tobytes())
C5,D5,E5,G5,B5,C6,D6,E6 = 523.25,587.33,659.25,783.99,987.77,1046.5,1174.66,1318.5
def buf(d): return np.zeros(int(d*SR))

def xmb_pad(dur):
    t = T(dur); fs = [130.81, 196.0, 293.66, 392.0, 587.33]; w = [1, .8, .6, .35, .2]
    y = sum(wi*(np.sin(2*np.pi*f*t) + .7*np.sin(2*np.pi*f*1.003*t + 1)) for f, wi in zip(fs, w))
    return y * (1 + .15*np.sin(2*np.pi*5*t))
