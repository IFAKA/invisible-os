"use client";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";
import { useRef } from "react";

gsap.registerPlugin(ScrollTrigger, useGSAP);

/** Apple's two-tone reading: four lines wake one by one as you scroll. The unread ones stay readable. */
export default function Privacy() {
  const root = useRef<HTMLElement>(null);
  useGSAP(() => {
    const mm = gsap.matchMedia();
    mm.add("(prefers-reduced-motion: no-preference)", () => {
      gsap.set(".pl", { opacity: 0.35 }); gsap.set(".pl-lead", { opacity: 0 });
      const tl = gsap.timeline({ defaults: { ease: "none" }, scrollTrigger: { trigger: root.current, start: "top top", end: "bottom bottom", scrub: 0.4 } });
      gsap.utils.toArray<HTMLElement>(".pl").forEach((l, i) => tl.to(l, { opacity: 1, duration: 0.6 }, i * 0.8));
      tl.to(".pl-lead", { opacity: 1, duration: 0.6 }, 3.4);
      tl.set({}, {}, 4.4);
    });
  }, { scope: root });
  return (
    <section ref={root} id="privacy" className="relative h-[240svh]">
      <div className="sticky top-0 mx-auto flex h-svh max-w-[1080px] flex-col items-center justify-center px-5 pb-6 pt-16 text-center">
        <p className="mb-[2svh] text-[clamp(17px,2.6svh,22px)] font-semibold">Privacy</p>
        {["microphone.", "cloud.", "account."].map((w) => (
          <p key={w} className="pl text-[clamp(32px,min(11svh,11vw),104px)] font-semibold leading-[1.06] tracking-[-0.04em]">No {w}</p>
        ))}
        <p className="pl-lead mt-[3svh] max-w-[28em] text-[clamp(17px,2.6svh,24px)] leading-snug text-mute">It does not listen. It speaks, and waits for your finger. Everything stays on the phone.</p>
      </div>
    </section>
  );
}
