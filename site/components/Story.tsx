"use client";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { Calendar, Ear, Mail, MessageCircle, Music, Phone, Timer } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { play } from "@/lib/sound";

gsap.registerPlugin(ScrollTrigger, useGSAP);

const BEATS = [
  { h: "Phones need your eyes.", d: "Every message, call and task starts with looking down. In the street. In the dark. At dinner." },
  { h: "So turn the screen off.", d: "The phone keeps working in your pocket. It just stops asking for your eyes." },
  { h: "Your ears become the screen.", d: "The phone speaks its menu, one item at a time." },
  { h: "Your button becomes the mouse.", d: "One click picks what you just heard." },
  { h: "Everything keeps working.", d: "Messages, calls, tasks, music. Even your laptop." },
] as const;
const ITEMS = ["Messages", "Tasks", "Timer"];
const CHIPS = ["Messages", "Calls", "Tasks", "Timer", "Music", "Laptop"];
const APPS = [[MessageCircle, "#30d158"], [Phone, "#0a84ff"], [Calendar, "#ff453a"], [Timer, "#ff9f0a"], [Music, "#bf5af2"], [Mail, "#64d2ff"]] as const;
const NOTES = [["#30d158", "WhatsApp", "Mom: Are you coming?"], ["#ff453a", "Calendar", "Standup in 5 minutes"], ["#0a84ff", "Missed call", "Alex"]] as const;

type S = { beat: number; idx: number; pick: boolean; burst: number };

/** Apple's method: one tall section, one pinned screen. Scroll is the clock. Text comes one beat at a time (never two at once); the phone and the button read the same clock. */
export default function Story() {
  const root = useRef<HTMLElement>(null);
  const [still, setStill] = useState(false);
  const [s, setS] = useState<S>({ beat: 0, idx: -1, pick: false, burst: 0 });
  const last = useRef(s);
  useEffect(() => setStill(matchMedia("(prefers-reduced-motion: reduce)").matches), []);

  useGSAP(() => {
    const mm = gsap.matchMedia();
    mm.add("(prefers-reduced-motion: no-preference)", () => {
      const beats = gsap.utils.toArray<HTMLElement>(".beat");
      const tl = gsap.timeline({
        defaults: { ease: "none" },
        scrollTrigger: {
          trigger: root.current, start: "top top", end: "bottom bottom", scrub: 0.4,
          onUpdate: (st) => {
            const t = st.progress * 5;
            const a = last.current;
            const idx = t >= 2.05 && t < 4 ? (t < 3 ? Math.min(2, Math.floor(((t - 2.05) / 0.85) * 3)) : 1) : -1; // Messages, Tasks, Timer; then it stays on Tasks
            const next: S = { beat: Math.min(4, Math.floor(t)), idx, pick: t > 3.4 && t < 4, burst: a.burst };
            if (next.pick && !a.pick) { next.burst++; play("pick"); }
            else if (next.idx >= 0 && next.idx !== a.idx) play("tick");
            if (next.beat === a.beat && next.idx === a.idx && next.pick === a.pick) return;
            last.current = next; setS(next);
          },
        },
      });
      beats.forEach((b, i) => {
        if (i > 0) tl.fromTo(b, { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.12, ease: "power2.out" }, i + 0.02);
        if (i < beats.length - 1) tl.to(b, { opacity: 0, y: -12, duration: 0.1, ease: "power2.in" }, i + 0.88);
      });
      tl.to(".lit", { opacity: 0, duration: 0.8 }, 1.1);                                            // the screen goes off
      tl.set({}, {}, 5);
    });
  }, { scope: root });

  if (still) return (
    <section id="story" className="mx-auto max-w-[760px] space-y-14 px-5 py-24 text-center">
      {BEATS.map((b) => <div key={b.h}><h2 className="text-4xl font-semibold tracking-[-0.03em]">{b.h}</h2><p className="mt-3 text-lg text-mute">{b.d}</p></div>)}
    </section>
  );

  return (
    <section ref={root} id="story" className="relative h-[560svh]">
      <div className="sticky top-0 flex h-svh flex-col items-center overflow-hidden px-5 pb-5 pt-[64px] text-center">
        <div className="relative h-[27svh] w-full max-w-[760px] shrink-0">
          {BEATS.map((b, i) => (
            <div key={b.h} className="beat absolute inset-0 flex flex-col items-center justify-center" style={{ opacity: i === 0 ? 1 : 0 }}>
              <h2 className="text-[clamp(26px,min(6.2svh,8.4vw),60px)] font-semibold leading-[1.07] tracking-[-0.03em] [text-wrap:balance]">{b.h}</h2>
              <p className="mt-[1.6svh] max-w-[30em] text-[clamp(16px,2.4svh,21px)] leading-snug text-mute [text-wrap:balance]">{b.d}</p>
            </div>
          ))}
        </div>

        <div className="relative min-h-0 w-full max-w-[860px] flex-1">
          <div aria-hidden className="mx-auto grid h-full grid-cols-[1fr_auto_1fr_auto_1fr] items-center gap-0.5 sm:gap-4">
            <Node label="You click" on={s.beat === 3}>
              <div key={s.burst} className={`relative grid size-[min(17vw,16svh,140px)] place-items-center rounded-full border bg-card transition-[transform,border-color] duration-150 ${s.pick ? "scale-90 border-blue" : "border-line"}`}>
                {s.pick && <span className="ring-once absolute inset-0 rounded-full border-2 border-blue" />}
                <span className={`size-[34%] rounded-full transition-colors duration-150 ${s.beat === 3 ? "bg-blue" : "bg-paper/25"}`} />
              </div>
              <p className={`mt-2 h-8 text-[clamp(15px,2.6svh,24px)] font-semibold tracking-[-0.02em] text-blue transition-opacity duration-200 ${s.pick ? "opacity-100" : "opacity-0"}`}>Picked</p>
            </Node>
            <Arrow show={s.beat >= 3} />

            <Node label={s.beat >= 1 ? "Phone, screen off" : "Your phone"} on pad>
              <div className="relative aspect-[9/19] w-[clamp(66px,20vw,150px)] rounded-[1.8rem] border-[3px] border-[#3a3a3c] bg-black">
                <div className="lit absolute inset-0 overflow-hidden rounded-[1.5rem] bg-gradient-to-b from-[#1d2b4d] via-[#2a1f4a] to-[#0d0d14] text-left">
                  <span className="mx-auto mt-2 block h-2.5 w-[32%] rounded-full bg-black" />
                  <div className="mt-[10%] space-y-1 px-2">
                    {NOTES.map(([c, a, t]) => (
                      <div key={a} className="flex items-center gap-1 rounded-lg bg-white/15 p-1">
                        <span className="size-3 shrink-0 rounded" style={{ background: c }} />
                        <span className="min-w-0 leading-tight"><b className="block truncate text-[7px]">{a}</b><span className="block truncate text-[7px] text-white/70">{t}</span></span>
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-x-2 bottom-2 grid grid-cols-3 gap-1.5">
                    {APPS.map(([Icon, c], k) => <span key={k} className="grid aspect-square place-items-center rounded-lg" style={{ background: c }}><Icon className="size-[45%] text-white" strokeWidth={2.2} /></span>)}
                  </div>
                </div>
                <span className={`absolute left-1/2 top-1/2 size-2 -translate-x-1/2 -translate-y-1/2 rounded-full bg-blue transition-opacity duration-500 ${s.beat >= 1 ? "opacity-100" : "opacity-0"}`} />
              </div>
            </Node>

            <Arrow show={s.beat >= 2} />

            <Node label="It speaks" on={s.beat === 2 || s.beat === 3}>
              <div className="relative grid size-[min(17vw,16svh,140px)] place-items-center rounded-full border border-line bg-card">
                {s.beat === 2 && <><span className="ring absolute inset-0 rounded-full border border-blue" /><span className="ring absolute inset-0 rounded-full border border-blue" style={{ animationDelay: "1.1s" }} /></>}
                <Ear className="size-[40%] text-paper" strokeWidth={1.6} />
              </div>
              <p className={`mt-2 h-8 text-[clamp(15px,2.6svh,24px)] font-semibold tracking-[-0.02em] transition-opacity duration-200 ${s.idx >= 0 ? "opacity-100" : "opacity-0"}`}>“{ITEMS[Math.max(0, s.idx)]}”</p>
            </Node>

          </div>
        </div>

        <div className="relative h-[8svh] w-full shrink-0" aria-live="off">
          <div className={`absolute inset-0 flex flex-wrap items-center justify-center gap-2 transition-opacity duration-300 ${s.beat === 4 ? "opacity-100" : "opacity-0"}`}>
            {CHIPS.map((c, k) => <span key={c} className="rounded-full border border-line bg-card px-3 py-1.5 text-[clamp(13px,2svh,16px)] transition-transform duration-500 ease-out" style={{ transform: s.beat === 4 ? "none" : "translateY(8px)", transitionDelay: `${k * 60}ms` }}>{c}</span>)}
          </div>
        </div>

        <div aria-hidden className="absolute bottom-2 left-1/2 flex -translate-x-1/2 gap-1.5">
          {BEATS.map((_, i) => <span key={i} className={`block h-1 rounded-full transition-all duration-300 ${s.beat === i ? "w-6 bg-paper" : "w-1.5 bg-paper/30"}`} />)}
        </div>
      </div>
    </section>
  );
}

function Node({ label, on, pad, children }: { label: string; on: boolean; pad?: boolean; children: React.ReactNode }) {
  return (
    <div className={`flex min-w-0 flex-col items-center transition-opacity duration-500 ${pad ? "pb-10" : ""} ${on ? "opacity-100" : "opacity-25"}`}>
      {children}
      <p className="mt-3 text-[clamp(11px,1.9svh,15px)] leading-tight text-mute">{label}</p>
    </div>
  );
}

function Arrow({ show, back }: { show: boolean; back?: boolean }) {
  return <span className={`-translate-y-[34px] text-[clamp(14px,3svh,28px)] text-mute transition-opacity duration-500 ${show ? "opacity-100" : "opacity-0"}`}>{back ? "←" : "→"}</span>;
}
