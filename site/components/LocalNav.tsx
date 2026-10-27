"use client";
import { useLenis } from "lenis/react";

const LINKS = [["overview", "Overview"], ["highlights", "Highlights"], ["screen", "Screen off"], ["laptop", "Laptop"], ["faq", "Questions"]] as const;

/** Apple-style local bar: name on the left, jumps in the middle, one button on the right. */
export default function LocalNav({ right }: { right: React.ReactNode }) {
  const lenis = useLenis();
  return (
    <header className="fixed inset-x-0 top-0 z-30 border-b border-line/60 bg-black/60 backdrop-blur-xl">
      <div className="mx-auto flex h-12 max-w-[1400px] items-center justify-between px-6 font-sans text-xs md:px-10">
        <a href="#overview" onClick={(e) => { e.preventDefault(); lenis?.scrollTo(0); }} className="text-[15px] font-semibold tracking-tight">Invisible OS</a>
        <nav className="hidden gap-7 text-paper/70 md:flex">
          {LINKS.map(([id, t]) => (
            <a key={id} href={`#${id}`} onClick={(e) => { e.preventDefault(); lenis?.scrollTo(`#${id}`); }} className="hover:text-paper">{t}</a>
          ))}
        </nav>
        <div className="flex items-center gap-5">{right}
          <a href="https://github.com/IFAKA/invisible-os" className="rounded-full bg-[#0071e3] px-3.5 py-1.5 text-xs font-medium text-white hover:bg-[#0077ed]">Get it</a>
        </div>
      </div>
    </header>
  );
}
