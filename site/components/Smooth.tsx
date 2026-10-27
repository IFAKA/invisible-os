"use client";
import { ReactLenis, useLenis } from "lenis/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useEffect, useRef } from "react";

gsap.registerPlugin(ScrollTrigger);

/** Lenis smooth scroll, driven by the GSAP ticker so ScrollTrigger and Lenis agree. */
export default function Smooth({ children }: { children: React.ReactNode }) {
  const lenis = useRef<{ lenis?: { raf: (t: number) => void } }>(null);
  useEffect(() => {
    const tick = (t: number) => lenis.current?.lenis?.raf(t * 1000);
    gsap.ticker.add(tick);
    gsap.ticker.lagSmoothing(0);
    return () => gsap.ticker.remove(tick);
  }, []);
  return (
    <ReactLenis root options={{ autoRaf: false, lerp: 0.09 }} ref={lenis as never}>
      <Sync />
      {children}
    </ReactLenis>
  );
}
function Sync() { useLenis(() => ScrollTrigger.update()); return null; }
