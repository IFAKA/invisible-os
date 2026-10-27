import Smooth from "@/components/Smooth";
import SceneLayer from "@/components/SceneLayer";
import Hero, { SoundToggle } from "@/components/Hero";
import ScanDemo from "@/components/ScanDemo";
import Reveal from "@/components/Reveal";

const label = "mb-7 font-sans text-xs uppercase tracking-[0.18em] text-mute";
const sec = "mx-auto flex min-h-screen max-w-[1200px] flex-col justify-center px-6 py-[14vh] md:px-[8vw]";
const h2 = "mb-6 text-[clamp(36px,5.5vw,76px)] leading-[1.03] tracking-[-0.02em]";
const lead = "max-w-[34em] text-[clamp(18px,1.6vw,22px)] text-paper/80";

const MOVES = [
  ["Click", "Pick what you just heard.", 1],
  ["Two", "One item back in a list.", 2],
  ["Three", "Go back.", 3],
  ["Hold", "Wake it, even on the lock screen.", 0],
] as const;

export default function Home() {
  return (
    <Smooth>
      <SceneLayer />
      <header className="fixed inset-x-0 top-0 z-20 flex items-center justify-between px-6 py-6 mix-blend-difference md:px-12">
        <span className="font-sans text-[13px] font-medium uppercase tracking-[0.18em]">Invisible OS</span>
        <SoundToggle />
      </header>
      <main className="relative z-10">
        <Hero />

        <section className={sec}>
          <Reveal><p className={label}>How it works</p><h2 className={h2}>It reads. You click.</h2>
            <p className={lead}>The phone speaks the options, one after another. When you hear the one you want, you click. That is the whole idea. It is called auto-scan, and it is how people with one switch use a computer.</p></Reveal>
          <ScanDemo />
        </section>

        <section className={sec}>
          <Reveal><p className={label}>One button, four moves</p></Reveal>
          <div className="grid gap-12 sm:grid-cols-2 lg:grid-cols-4">
            {MOVES.map(([t, d, n]) => (
              <Reveal key={t}>
                <div className="flex h-7 items-center gap-2.5">
                  {n ? Array.from({ length: n }, (_, k) => <span key={k} className="block size-5 rounded-full bg-paper" />) : <span className="block h-5 w-24 rounded-full bg-paper" />}
                </div>
                <h3 className="mb-2 mt-5 font-sans text-[13px] font-semibold uppercase tracking-[0.14em]">{t}</h3>
                <p className="text-base text-mute">{d}</p>
              </Reveal>
            ))}
          </div>
        </section>

        <section className={sec}>
          <Reveal><p className={label}>What it will never do</p></Reveal>
          {["microphone.", "cloud.", "account.", "screen needed."].map((w) => (
            <Reveal key={w}><p className="text-[clamp(44px,8.5vw,112px)] leading-[1.04] tracking-[-0.03em]"><em className="text-mute">No</em> {w}</p></Reveal>
          ))}
          <Reveal><p className={`${lead} mt-9`}>It does not listen. It speaks, and waits for your finger. Everything stays on the phone.</p></Reveal>
        </section>

        <section className={sec}>
          <Reveal><p className={label}>New: Laptop</p><h2 className={h2}>Your agents, in your ear.</h2>
            <p className={lead}>Many windows on your laptop, each with an agent at work. Away from the desk, the earbud tells you which one needs you, reads what it asks, and sends the answer.</p></Reveal>
          <Reveal>
            <div className="mt-11 max-w-[560px] rounded-[18px] border border-line bg-[#070707] p-7">
              <p className="font-sans text-[13px] tracking-wide text-mute">Battery-free device ideas <b className="ml-2 font-semibold text-paper">needs you</b></p>
              <p className="my-5 text-[clamp(20px,2.2vw,26px)]">“Pick the mini’s cell. Reply use AM-1454, or keep it small.”</p>
              <p className="font-sans text-sm text-mute">Click: <b className="font-semibold text-paper">use AM-1454</b> · Sent.</p>
            </div>
          </Reveal>
        </section>

        <section className={sec}>
          <Reveal><h2 className={h2}>Open source. Early. Used every day.</h2>
            <p className={lead}>Android 10 or newer. Built in Kotlin. MIT license.</p>
            <a href="https://github.com/IFAKA/invisible-os" className="mt-9 inline-block w-max rounded-full bg-paper px-8 py-5 font-sans text-[13px] font-medium uppercase tracking-[0.16em] text-ink transition-transform hover:-translate-y-0.5">Get it on GitHub</a></Reveal>
        </section>
      </main>
      <footer className="relative z-10 px-6 pb-14 pt-20 font-sans text-[13px] text-mute md:px-[8vw]">For people who cannot, or do not want to, look at the phone.</footer>
    </Smooth>
  );
}
