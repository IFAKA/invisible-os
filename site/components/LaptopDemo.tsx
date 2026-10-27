"use client";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { Ear } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { play } from "@/lib/sound";

gsap.registerPlugin(ScrollTrigger, useGSAP);

const BEATS = [
  { h: "Your agents.\nIn your ear.", d: "Many windows on your laptop, each with an agent at work." },
  { h: "One needs you.", d: "The earbud tells you which one. You do not look." },
  { h: "It reads what it asks.", d: "Claude's own short summary, spoken to you." },
  { h: "You click. It is sent.", d: "Your answer is typed into that window on the laptop." },
] as const;
const SAID = ["", "“os, needs you”", "“Say go for the design doc.”", "Sent to os."];

type S = { beat: number; pick: boolean };

/** Same method as Story: one pinned screen, scroll is the clock. Left is the laptop, right is what you hear and press. */
export default function LaptopDemo() {
  const root = useRef<HTMLElement>(null);
  const [still, setStill] = useState(false);
  const [s, setS] = useState<S>({ beat: 0, pick: false });
  const last = useRef(s);
  useEffect(() => setStill(matchMedia("(prefers-reduced-motion: reduce)").matches), []);

  useGSAP(() => {
    const mm = gsap.matchMedia();
    mm.add("(prefers-reduced-motion: no-preference)", () => {
      ScrollTrigger.create({
        trigger: root.current, start: "top top", end: "bottom bottom",
        onUpdate: (st) => {
          const t = st.progress * 4;
          const next: S = { beat: Math.min(3, Math.floor(t)), pick: t > 3.35 };
          const a = last.current;
          if (next.beat === a.beat && next.pick === a.pick) return;
          if (next.pick && !a.pick) play("pick"); else if (next.beat !== a.beat && next.beat > 0 && !next.pick) play("tick");
          last.current = next; setS(next);
        },
      });
    });
  }, { scope: root });

  if (still) return (
    <section id="laptop" className="mx-auto max-w-[760px] space-y-12 px-5 py-24 text-center">
      {BEATS.map((b) => <div key={b.h}><h2 className="whitespace-pre-line text-4xl font-semibold tracking-[-0.03em]">{b.h}</h2><p className="mt-3 text-lg text-mute">{b.d}</p></div>)}
    </section>
  );

  const rows = [
    { n: "os", state: s.beat === 0 ? "working" : s.beat < 3 || !s.pick ? "needs you" : "working" },
    { n: "hardware", state: "done" },
    { n: "wereable", state: "working" },
  ];
  const needs = (st: string) => st === "needs you";

  return (
    <section ref={root} id="laptop" className="relative h-[440svh]">
      <div className="sticky top-0 flex h-svh flex-col items-center justify-center overflow-hidden px-5 pb-[56px] pt-[56px] text-center">
        <div className="relative h-[27svh] w-full max-w-[760px] shrink-0 max-sm:h-[22svh]">
          {BEATS.map((b, i) => (
            <div key={b.h} className="absolute inset-0 flex flex-col items-center justify-center transition-[opacity,transform] duration-300" style={{ opacity: s.beat === i ? 1 : 0, transform: s.beat === i ? "none" : "translateY(10px)", transitionDelay: s.beat === i ? "250ms" : "0ms" }}>
              <h2 className="whitespace-pre-line text-[clamp(28px,min(6.2svh,8.4vw),60px)] font-semibold leading-[1.07] tracking-[-0.03em] [text-wrap:balance]">{b.h}</h2>
              <p className="mt-[1.6svh] max-w-[30em] text-[clamp(16px,2.4svh,21px)] leading-snug text-mute [text-wrap:balance]">{b.d}</p>
            </div>
          ))}
        </div>

        <div aria-hidden className="grid w-full max-w-[860px] shrink-0 grid-cols-1 gap-3 sm:gap-4 sm:grid-cols-[1.25fr_1fr] sm:items-stretch">
          <div className="rounded-[20px] border border-line bg-card p-[clamp(12px,2.4svh,22px)] text-left">
            <p className="mb-[1.6svh] text-[clamp(11px,1.8svh,13px)] uppercase tracking-[0.08em] text-mute">Your laptop</p>
            <ul className="space-y-[1.2svh]">
              {rows.map((r) => (
                <li key={r.n} className={`flex items-center justify-between rounded-xl border px-3 py-[1.3svh] text-[clamp(14px,2.3svh,18px)] transition-colors duration-300 ${needs(r.state) ? "border-blue bg-blue/10" : "border-line"}`}>
                  <span className="font-medium">{r.n}</span>
                  <span className={`text-[0.85em] ${needs(r.state) ? "font-semibold text-blue" : "text-mute"}`}>
                    {s.pick && r.n === "os" ? <span className="font-mono text-paper">go ⏎</span> : r.state}
                  </span>
                </li>
              ))}
            </ul>
          </div>

          <div className="flex flex-col items-center justify-center rounded-[20px] border border-line bg-card p-[clamp(12px,2.4svh,22px)]">
            <p className="mb-[1.6svh] text-[clamp(11px,1.8svh,13px)] uppercase tracking-[0.08em] text-mute">Your ear and button</p>
            <div className="flex items-center gap-4">
              <span className={`relative grid size-[clamp(44px,8svh,64px)] place-items-center rounded-full border border-line transition-opacity duration-300 ${s.beat === 1 || s.beat === 2 ? "opacity-100" : "opacity-30"}`}>
                {(s.beat === 1 || s.beat === 2) && <span className="ring absolute inset-0 rounded-full border border-blue" />}
                <Ear className="size-1/2" strokeWidth={1.6} />
              </span>
              <span className={`grid size-[clamp(44px,8svh,64px)] place-items-center rounded-full border bg-black transition-[transform,border-color,opacity] duration-150 ${s.pick ? "scale-90 border-blue opacity-100" : s.beat === 3 ? "border-line opacity-100" : "border-line opacity-30"}`}>
                <span className={`size-1/3 rounded-full ${s.beat === 3 ? "bg-blue" : "bg-paper/30"}`} />
              </span>
            </div>
            <p className="mt-[1.5svh] min-h-[2.6em] text-[clamp(16px,2.8svh,24px)] font-semibold leading-snug tracking-[-0.02em] [text-wrap:balance]">{SAID[s.beat]}</p>
          </div>
        </div>

        <div aria-hidden className="mt-[3svh] flex gap-1.5">
          {BEATS.map((_, i) => <span key={i} className={`block h-1 rounded-full transition-all duration-300 ${s.beat === i ? "w-6 bg-paper" : "w-1.5 bg-paper/30"}`} />)}
        </div>
      </div>
    </section>
  );
}
