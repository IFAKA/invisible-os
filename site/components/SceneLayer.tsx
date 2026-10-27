"use client";
import dynamic from "next/dynamic";
import { useEffect, useState } from "react";

const Scene = dynamic(() => import("./Scene"), { ssr: false });

/** The 3D button sits behind the page. Mounted after first paint; skipped when the person asks for less motion. */
export default function SceneLayer() {
  const [on, setOn] = useState(false);
  useEffect(() => {
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const id = setTimeout(() => setOn(true), 300);
    return () => clearTimeout(id);
  }, []);
  return <div aria-hidden className="pointer-events-none fixed inset-0 z-0">{on && <Scene />}</div>;
}
