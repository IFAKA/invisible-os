"use client";
import gsap from "gsap";
import { SplitText } from "gsap/SplitText";
import { useGSAP } from "@gsap/react";
import { useRef, useState } from "react";
import { hit } from "@/lib/pulse";
import { play, setSound, soundOn } from "@/lib/sound";

gsap.registerPlugin(SplitText, useGSAP);

const LINES = ["Hear that? That is a click.", "Now scroll. It is simple on purpose.", "One button is enough."];

export function SoundToggle() {
  const [on, setOn] = useState(false);
  return (
    <button
      aria-pressed={on}
      onClick={() => { setSound(!soundOn); setOn(soundOn); play("tap"); }}
      className="font-sans text-xs uppercase tracking-[0.14em] text-mute hover:text-paper aria-pressed:text-paper"
    >
      {on ? "Sound on" : "Sound off"}
    </button>
  );
}

export default function Hero() {
  const root = useRef<HTMLElement>(null);
  const [n, setN] = useState(0);
  useGSAP(() => {
    const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce) return;
    const split = SplitText.create("h1", { type: "lines,words", mask: "lines", autoSplit: true });
    gsap.from(split.words, { yPercent: 110, duration: 1.2, stagger: 0.07, ease: "expo.out", delay: 0.2 });
    gsap.from(".hero-in", { opacity: 0, y: 16, duration: 1, stagger: 0.12, ease: "power3.out", delay: 0.9 });
  }, { scope: root });

  const press = () => { hit(); play("tap"); setN((v) => Math.min(v + 1, LINES.length)); };
  return (
    <section ref={root} className="mx-auto flex min-h-screen max-w-[1200px] flex-col justify-center px-6 py-[14vh] md:px-[8vw]">
      <h1 className="text-[clamp(48px,9.5vw,136px)] leading-[0.96] tracking-[-0.03em]">
        Your phone.<br /><em className="text-mute">Without the screen.</em>
      </h1>
      <p className="hero-in mt-8 text-[clamp(20px,2.4vw,30px)] text-mute">One button. Your ears. Nothing else.</p>
      <button onClick={press} aria-label="Press the button" className="hero-in mt-14 flex w-max items-center gap-5 font-sans text-xs uppercase tracking-[0.2em] text-mute active:scale-95">
        <span className="block size-16 rounded-full border border-paper/60" />
        Press
      </button>
      <p className="hero-in mt-4 font-sans text-[13px] text-mute">{n ? LINES[n - 1] : "Click, tap or press Enter."}</p>
    </section>
  );
}
