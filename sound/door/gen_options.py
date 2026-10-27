from lib import *
# 1 GLASS: FM bells in fifths. Up in, down out.
b = buf(1.2)
for i, f in enumerate([C5, G5, C6]): place(b, bell(f, 1.0, .22 + .06*i)*(.7+.15*i), i*.09)
save('1_glass_enter', b)
b = buf(1.6)
for i, f in enumerate([C6, G5, C5]): place(b, bell(f, 1.3, .3 - .05*i, idx=1.8)*(1-.15*i), i*.14)
save('1_glass_leave', lp_sweep(b, 9000, 1500), rt=1.2)

# 2 BLOOM: a soft chord (Cmaj9) swells open; leaving it closes and fades.
chord = [261.63*1.0, 392.0, 587.33, 659.25, 987.77]
n = int(1.0*SR); t = T(1.0)
pad = sum(np.sin(2*np.pi*f*t) + .6*np.sin(2*np.pi*f*1.004*t) for f in chord)
pad_in = pad * np.minimum(1, t/.30)**2 * np.exp(-np.maximum(0, t-.30)/.25)
save('2_bloom_enter', lp_sweep(pad_in, 700, 6000), rt=1.1, wet=.35)
pad_out = pad * np.minimum(1, t/.02) * np.exp(-t/.28)
save('2_bloom_leave', lp_sweep(pad_out, 5000, 400), rt=1.1, wet=.35)

# 3 DROP: water-drop glide up in, down out (the pitch is the direction).
def glide(f0, f1, dur, tau):
    t = T(dur); f = f0 * (f1/f0) ** (1-np.exp(-t/(dur*.25))) ; ph = 2*np.pi*np.cumsum(f)/SR
    return (np.sin(ph) + .2*np.sin(2*ph)) * adsr(len(t), .006, tau)
save('3_drop_enter', glide(500, 1250, .5, .16), rt=.8)
save('3_drop_leave', glide(1250, 480, .6, .2), rt=.8)

# 4 DOCK (AirPods-like): soft low thump + two chime notes; leaving plays them back reversed, the thump last (a lid closing).
def thump(): t = T(.12); return np.sin(2*np.pi*(70+60*np.exp(-t/.02))*t) * np.exp(-t/.03) * .9
b = buf(1.3); place(b, thump(), 0); place(b, bell(G5, .9, .2, idx=1.5), .06); place(b, bell(D6, 1.0, .28, idx=1.5), .20)
save('4_dock_enter', b, rt=.9)
b = buf(1.5); place(b, bell(D6, .8, .16, idx=1.5), 0); place(b, bell(G5, .9, .22, idx=1.5), .14); place(b, thump(), .36)
save('4_dock_leave', b, rt=.9)

# 5 SPACE: a rising air sweep lands on one bell (you walk in); a bell then a falling sweep (you walk out).
sw = bp_noise(.34, 500, 5000)*np.minimum(1, T(.34)/.34)**2*.5
b = buf(1.4); place(b, sw, 0); place(b, bell(C6, 1.0, .3, idx=1.6), .30); place(b, bell(G5, 1.0, .3, idx=1.2)*.5, .30)
save('5_space_enter', b, rt=1.0)
sw = bp_noise(.45, 4500, 400)*np.exp(-T(.45)/.2)*.5
b = buf(1.5); place(b, bell(G5, 1.0, .3, idx=1.2), 0); place(b, bell(C5, 1.0, .3, idx=1.2)*.6, .02); place(b, sw, .18)
save('5_space_leave', b, rt=1.0)

# 6 XMB (PS3 mood): a deep drone swells slowly with airy shimmer and a glassy ping on top; leaving it sinks and the light closes.
def xmb_pad(dur):
    t = T(dur); fs = [130.81, 196.0, 293.66, 392.0, 587.33]; w = [1, .8, .6, .35, .2]
    y = sum(wi*(np.sin(2*np.pi*f*t) + .7*np.sin(2*np.pi*f*1.003*t + 1)) for f, wi in zip(fs, w))
    return y * (1 + .15*np.sin(2*np.pi*5*t))  # slow shimmer
t = T(1.3); p = xmb_pad(1.3)
e = np.minimum(1, t/.7)**2 * np.exp(-np.maximum(0, t-.7)/.25)
b = lp_sweep(p*e, 300, 4500) + .25*bp_noise(1.3, 1500, 6000, q=1.5)*np.minimum(1, t/.9)**3*np.exp(-np.maximum(0, t-.9)/.2)
b = np.pad(b, (0, int(.3*SR))); place(b, bell(G5, 1.0, .35, idx=1.2)*.9, .62)
save('6_xmb_enter', b, rt=1.6, wet=.4)
t = T(1.2); p = xmb_pad(1.2)
e = np.minimum(1, t/.03) * np.exp(-t/.4)
b = lp_sweep(p*e, 4000, 200); b = np.pad(b, (0, int(.3*SR))); place(b, bell(C5, 1.0, .3, idx=1.0)*.7, 0)
save('6_xmb_leave', b, rt=1.6, wet=.4)
