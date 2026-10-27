"use client";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { useRef } from "react";

gsap.registerPlugin(ScrollTrigger, useGSAP);

/** Four lines wake one by one as you scroll. The unread ones stay readable (large text, 40% light). */
export default function Privacy() {
  const root = useRef<HTMLElement>(null);
  useGSAP(() => {
    const mm = gsap.matchMedia();
    mm.add("(prefers-reduced-motion: no-preference)", () => {
      const tl = gsap.timeline({ defaults: { ease: "power2.out" }, scrollTrigger: { trigger: root.current, start: "top top", end: "bottom bottom", scrub: 0.5 } });
      gsap.utils.toArray<HTMLElement>(".pl").forEach((l, i) => tl.fromTo(l, { opacity: 0.4, x: -16 }, { opacity: 1, x: 0, duration: 1 }, i));
      tl.fromTo(".pl-lead", { opacity: 0, y: 20 }, { opacity: 1, y: 0, duration: 1 }, 4);
      tl.set({}, {}, 5.4);
    });
  }, { scope: root });
  return (
    <section ref={root} className="relative h-[260svh]">
      <div className="sticky top-0 mx-auto flex h-svh max-w-[1400px] flex-col justify-center overflow-hidden px-6 pb-8 pt-16 md:px-[8vw]">
        <p className="mb-[2vh] font-sans text-xs uppercase tracking-[0.18em] text-mute">Privacy</p>
        {["microphone.", "cloud.", "account.", "screen needed."].map((w) => (
          <p key={w} className="pl text-[clamp(36px,11vh,120px)] leading-[1.04] tracking-[-0.03em]"><em className="text-mute">No</em> {w}</p>
        ))}
        <p className="pl-lead mt-[3vh] max-w-[30em] text-[clamp(16px,2.5vh,22px)] leading-snug text-paper/85">It does not listen. It speaks, and waits for your finger. Everything stays on the phone.</p>
      </div>
    </section>
  );
}
