"use client";
import { motion } from "motion/react";

/** Fades and lifts in once when it scrolls into view. Opacity and transform only. */
export default function Reveal({ children, delay = 0, className }: { children: React.ReactNode; delay?: number; className?: string }) {
  return (
    <motion.div className={className} initial={{ opacity: 0, y: 20 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, amount: 0.3 }} transition={{ duration: 0.7, delay, ease: [0.22, 0.61, 0.36, 1] }}>
      {children}
    </motion.div>
  );
}
