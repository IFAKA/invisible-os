"use client";
import { useRef, useState } from "react";
import { hit } from "@/lib/pulse";
import { play } from "@/lib/sound";

const APPS = ["Messages", "Tasks", "Timer", "Music", "Phone", "Games"];

/** Apple's "move the slider to open and close", turned around: move it to turn the screen off. It keeps working. */
export default function ScreenSlider() {
  const [v, setV] = useState(0);
  const done = useRef(false);
  const off = v >= 100;
  const change = (n: number) => {
    setV(n);
    if (n >= 100 && !done.current) { done.current = true; hit(); play("pick"); }
    if (n < 100) done.current = false;
  };
  return (
    <div className="flex w-full flex-col items-center gap-[2.2vh]">
      <div className="relative h-[40svh] max-h-[420px] w-[calc(40svh*0.5)] max-w-[210px] rounded-[2.2rem] border border-paper/25 bg-[#050505] p-3 shadow-[0_0_80px_rgba(255,255,255,0.06)]">
        <div className="grid h-full grid-cols-2 content-center gap-2 rounded-[1.6rem] bg-[#111] p-3 font-sans text-[10px] transition-opacity duration-200" style={{ opacity: 1 - v / 100 }}>
          {APPS.map((a) => <span key={a} className="rounded-xl bg-white/10 py-3.5 text-center text-paper/80">{a}</span>)}
        </div>
        <span className="absolute inset-0 grid place-items-center font-sans text-xs uppercase tracking-[0.2em] text-paper transition-opacity duration-300" style={{ opacity: off ? 1 : 0 }}>Listening</span>
      </div>
      <input aria-label="Screen" type="range" min={0} max={100} value={v} onChange={(e) => change(+e.target.value)} className="h-1 w-[min(320px,70vw)] cursor-pointer appearance-none rounded-full bg-line accent-white [&::-webkit-slider-thumb]:size-6 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-paper" />
      <p className="font-sans text-xs text-mute">{off ? "Screen off. Still works." : "Move the slider to turn the screen off."}</p>
    </div>
  );
}
