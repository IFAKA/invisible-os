"use client";
import dynamic from "next/dynamic";
import { useInView } from "motion/react";
import { useEffect, useRef, useState } from "react";

const Earbud = dynamic(() => import("./Earbud"), { ssr: false });

/** A lit stage for the earbud: a soft light on the floor, and the 3D canvas only while it is on screen (and motion is welcome). */
export default function Stage({ mode }: { mode: "hero" | "story" }) {
  const ref = useRef<HTMLDivElement>(null);
  const near = useInView(ref, { margin: "200px" });
  const [calm, setCalm] = useState(true);
  useEffect(() => setCalm(matchMedia("(prefers-reduced-motion: reduce)").matches), []);
  return (
    <div ref={ref} aria-hidden className="pointer-events-none absolute inset-0">
      <div className="absolute left-1/2 top-1/2 aspect-square h-[88%] -translate-x-1/2 -translate-y-1/2 rounded-full bg-[radial-gradient(closest-side,rgba(110,150,255,0.24),rgba(110,150,255,0.06)_60%,transparent)]" />
      {near && !calm && <Earbud mode={mode} />}
    </div>
  );
}
