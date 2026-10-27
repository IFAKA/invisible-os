"use client";
import { motion } from "motion/react";
import Orb from "./Orb";

/** One name, one line, one picture: the button, glowing. Same order as Apple's top of page. */
export default function Hero() {
  return (
    <section id="overview" className="relative mx-auto flex h-svh max-w-[1080px] overflow-x-clip flex-col items-center justify-center px-5 pb-8 pt-16 text-center">
      <motion.p initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.7, delay: 0.1 }} className="text-[clamp(18px,2.8vh,24px)] font-semibold">Invisible OS</motion.p>
      <motion.h1 initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.8, delay: 0.2, ease: [0.22, 0.61, 0.36, 1] }} className="mt-[1.5vh] text-[clamp(40px,min(9vw,12vh),96px)] font-semibold leading-[1.04] tracking-[-0.035em] [text-wrap:balance]">
        Your phone.<br />Without looking.
      </motion.h1>
      <motion.p initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.8, delay: 0.5 }} className="mt-[2vh] max-w-[26em] text-[clamp(17px,2.6vh,24px)] leading-snug text-mute">
        One earbud button. The phone speaks. You click.
      </motion.p>
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.8, delay: 0.7 }} className="mt-[3vh] flex items-center gap-6 text-[17px]">
        <a href="https://github.com/IFAKA/invisible-os" className="rounded-full bg-[#0071e3] px-5 py-2.5 font-medium text-white hover:bg-[#0077ed]">Get it</a>
        <a href="#story" className="text-blue hover:underline">See how it works ›</a>
      </motion.div>
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 1.2, delay: 0.6 }} className="relative mt-[2vh] w-full flex-1">
        <Orb />
      </motion.div>
    </section>
  );
}
