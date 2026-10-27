"use client";
import { useLenis } from "lenis/react";
import SoundToggle from "./Sound";

const LINKS = [["overview", "Overview"], ["story", "How it works"], ["highlights", "Highlights"], ["laptop", "Laptop"], ["specs", "Specs"]] as const;

/** Apple's local bar: product name left, jumps right, one blue button. */
export default function LocalNav() {
  const lenis = useLenis();
  const go = (id: string) => (e: React.MouseEvent) => { e.preventDefault(); lenis?.scrollTo(id === "overview" ? 0 : `#${id}`); };
  return (
    <header className="fixed inset-x-0 top-0 z-30 border-b border-white/10 bg-black/70 backdrop-blur-xl">
      <div className="mx-auto flex h-12 max-w-[1080px] items-center justify-between px-5 text-xs">
        <a href="#overview" onClick={go("overview")} className="text-[17px] font-semibold tracking-tight">Invisible OS</a>
        <nav className="hidden gap-7 text-paper/80 md:flex">
          {LINKS.map(([id, t]) => <a key={id} href={`#${id}`} onClick={go(id)} className="hover:text-paper">{t}</a>)}
        </nav>
        <div className="flex items-center gap-5">
          <SoundToggle />
          <a href="https://github.com/IFAKA/invisible-os" className="rounded-full bg-[#0071e3] px-3.5 py-1.5 font-medium text-white hover:bg-[#0077ed]">Get it</a>
        </div>
      </div>
    </header>
  );
}
