"use client";
import { motion } from "motion/react";

/** Fades and lifts its children in once, when they scroll into view. */
export default function Reveal({ children }: { children: React.ReactNode }) {
  return (
    <motion.div initial={{ opacity: 0, y: 24 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, amount: 0.25 }} transition={{ duration: 0.9, ease: [0.2, 0.7, 0.1, 1] }}>
      {children}
    </motion.div>
  );
}
