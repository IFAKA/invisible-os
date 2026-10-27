"use client";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { useEffect, useRef, useState } from "react";
import Stage from "./Stage";
import { hit, story } from "@/lib/pulse";
import { play } from "@/lib/sound";

gsap.registerPlugin(ScrollTrigger, useGSAP);

const BEATS = [
  { k: "Today", h: "Phones need your eyes.", d: "Every message, call and task starts with looking down. In the street. In the dark. At dinner." },
  { k: "The button you already have", h: "One click.", d: "Your earbud has a button. Invisible OS turns it into the whole phone." },
  { k: "It reads. You click.", h: "Hear it. Pick it.", d: "" },
  { k: "Screen off", h: "Still the whole phone.", d: "Pocket it. Messages, calls, tasks and your laptop agents all answer to one button." },
] as const;
const ITEMS = ["Messages", "Tasks", "Timer"];

/** The Apple way: a tall section with one pinned screen. Scroll is the clock; text and the 3D earbud both read it, so they never drift. */
export default function Story() {
  const root = useRef<HTMLElement>(null);
  const [still, setStill] = useState(false);
  const [s, setS] = useState({ beat: 0, idx: -1, pick: false });
  const last = useRef(s);
  useEffect(() => setStill(matchMedia("(prefers-reduced-motion: reduce)").matches), []);

  useGSAP(() => {
    const mm = gsap.matchMedia();
    mm.add("(prefers-reduced-motion: no-preference)", () => {
      const beats = gsap.utils.toArray<HTMLElement>(".beat");
      const tl = gsap.timeline({
        defaults: { ease: "none" },
        scrollTrigger: {
          trigger: root.current, start: "top top", end: "bottom bottom", scrub: 0.5,
          onUpdate: (st) => {
            const p = st.progress; story.p = p;
            const local = gsap.utils.clamp(0, 1, (p * 4 - 2) / 0.7);
            const next = { beat: Math.min(3, Math.floor(p * 4)), idx: p * 4 > 1.9 && p * 4 < 2.9 ? Math.min(2, Math.floor(local * 3)) : -1, pick: local >= 1 && p * 4 < 2.9 };
            const a = last.current;
            if (next.beat === a.beat && next.idx === a.idx && next.pick === a.pick) return;
            last.current = next; setS(next);
            if (next.pick && !a.pick) { hit(); play("pick"); }
            else if (next.idx >= 0 && next.idx !== a.idx) { hit(); play("tick"); }
          },
        },
      });
      beats.forEach((b, i) => {
        if (i > 0) tl.fromTo(b, { opacity: 0, y: 48 }, { opacity: 1, y: 0, duration: 0.3, ease: "power2.out" }, i - 0.3);
        if (i < beats.length - 1) tl.to(b, { opacity: 0, y: -48, duration: 0.3, ease: "power2.in" }, i + 0.7);
      });
      tl.set({}, {}, 4);
    });
  }, { scope: root });

  const wrap = still ? "px-6 py-24 md:px-[8vw]" : "sticky top-0 h-svh overflow-hidden";
  return (
    <section ref={root} id="story" className={still ? "" : "relative h-[480svh]"}>
      <div className={wrap}>
        <div className="mx-auto grid h-full max-w-[1400px] grid-rows-[46%_1fr] gap-2 px-6 pb-8 pt-16 md:grid-cols-2 md:grid-rows-1 md:gap-16 md:px-[8vw]">
          <div className="relative order-1 md:order-2">{!still && <Stage mode="story" />}</div>
          <div className={`order-2 md:order-1 ${still ? "space-y-16" : "relative"}`}>
            {BEATS.map((b, i) => (
              <div key={b.k} className={`beat ${still ? "" : "absolute inset-0 flex flex-col justify-center"}`} style={{ opacity: i === 0 || still ? 1 : 0 }}>
                <p className="mb-[2vh] font-sans text-xs uppercase tracking-[0.18em] text-mute">{b.k}</p>
                <h2 className="text-[clamp(34px,7vh,84px)] leading-[1.03] tracking-[-0.02em]">{b.h}</h2>
                {b.d && <p className="mt-[2.4vh] max-w-[26em] text-[clamp(16px,2.5vh,22px)] leading-snug text-paper/85">{b.d}</p>}
                {i === 2 && (
                  <ul className="mt-[3vh]" aria-label="Example menu">
                    {ITEMS.map((it, k) => {
                      const on = s.idx === k;
                      return (
                        <li key={it} className={`flex items-center justify-between border-t border-line py-[1.2vh] text-[clamp(22px,4.2vh,40px)] transition-colors duration-150 ${on ? "text-paper" : "text-paper/40"}`}>
                          {it}<span className={`block size-3 rounded-full transition-all duration-150 ${on ? (s.pick ? "scale-125 bg-[#4d8dff]" : "bg-paper") : "scale-0"}`} />
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            ))}
          </div>
        </div>
        {!still && (
          <div aria-hidden className="absolute bottom-6 left-1/2 flex -translate-x-1/2 gap-2">
            {BEATS.map((_, i) => <span key={i} className={`block h-1 rounded-full transition-all duration-300 ${s.beat === i ? "w-8 bg-paper" : "w-2 bg-paper/30"}`} />)}
          </div>
        )}
      </div>
    </section>
  );
}
