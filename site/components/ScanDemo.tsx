"use client";
import { motion, useInView } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { hit } from "@/lib/pulse";
import { play } from "@/lib/sound";

const ITEMS = ["Messages", "Tasks", "Timer", "Volume", "Status", "Games"];

/** The menu reads itself, one item at a time. Click when you hear the one you want. */
export default function ScanDemo() {
  const ref = useRef<HTMLOListElement>(null);
  const inView = useInView(ref, { amount: 0.6 });
  const [i, setI] = useState(0);
  const [picked, setPicked] = useState<number | null>(null);

  useEffect(() => {
    if (!inView || picked !== null) return;
    play("tick");
    const id = setTimeout(() => setI((v) => (v + 1) % ITEMS.length), 1300);
    return () => clearTimeout(id);
  }, [inView, picked, i]);

  useEffect(() => {
    if (picked === null) return;
    const id = setTimeout(() => setPicked(null), 3200);
    return () => clearTimeout(id);
  }, [picked]);

  const choose = () => { if (picked !== null) return; setPicked(i); hit(); play("pick"); };

  return (
    <div>
      <ol ref={ref} onClick={choose} className="mt-12 max-w-[520px] cursor-pointer border-t border-line" aria-label="Demo of the menu">
        {ITEMS.map((t, k) => {
          const on = k === i && picked === null, done = k === picked;
          return (
            <motion.li
              key={t}
              animate={{ color: done ? "#000" : on ? "#f4f4f1" : "#4a4a47", paddingLeft: on || done ? 16 : 0, backgroundColor: done ? "#f4f4f1" : "#00000000" }}
              transition={{ duration: 0.3 }}
              className="flex items-baseline justify-between border-b border-line py-5 text-[clamp(24px,3vw,36px)]"
            >
              {t}
              <motion.span animate={{ opacity: on || done ? 1 : 0, scale: on || done ? 1 : 0.3 }} className={`mr-4 block size-2.5 rounded-full ${done ? "bg-ink" : "bg-paper"}`} />
            </motion.li>
          );
        })}
      </ol>
      <p className="mt-4 font-sans text-[13px] text-mute">{picked !== null ? `You picked ${ITEMS[picked]}. That was one click.` : "Click when you hear what you want."}</p>
    </div>
  );
}
