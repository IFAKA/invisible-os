import Smooth from "@/components/Smooth";
import SceneLayer from "@/components/SceneLayer";
import Hero, { SoundToggle } from "@/components/Hero";
import ScanDemo from "@/components/ScanDemo";
import ScreenSlider from "@/components/ScreenSlider";
import LocalNav from "@/components/LocalNav";
import Faq from "@/components/Faq";
import Reveal from "@/components/Reveal";

// Every section is exactly one screen tall. Sizes follow the screen height (vh) so nothing is cut.
const sec = "relative mx-auto flex h-svh max-w-[1400px] flex-col justify-center overflow-hidden px-6 pb-8 pt-16 md:px-[8vw]";
const two = "grid items-center gap-[3vh] md:grid-cols-2 md:gap-16";
const label = "mb-[2vh] font-sans text-xs uppercase tracking-[0.18em] text-mute";
const h2 = "text-[clamp(32px,7.2vh,84px)] leading-[1.03] tracking-[-0.02em]";
const lead = "mt-[2.4vh] max-w-[30em] text-[clamp(16px,2.5vh,22px)] leading-snug text-paper/75";

const HIGHLIGHTS = [
  ["One button", "Click, two, three, hold. That is all you need."],
  ["It reads to you", "Options are spoken one by one. Click on the one you want."],
  ["No microphone", "It never listens. No cloud, no account."],
  ["Your messages", "Hear WhatsApp, Teams and WeChat. Reply by click."],
  ["Your laptop", "Know which agent needs you. Answer from the earbud."],
  ["Open source", "Android, Kotlin, MIT. Make your own program for it."],
] as const;

const MOVES = [["Click", "Pick what you just heard.", 1], ["Two", "One item back in a list.", 2], ["Three", "Go back.", 3], ["Hold", "Wake it, even on the lock screen.", 0]] as const;
const APPS = [["Messages", "Read and reply to chats. Spanish or English."], ["Tasks", "Add and finish a to-do list by ear."], ["Timer", "Set it, and hear it end."], ["Status", "Time, battery, weather, next event."], ["Games", "Duel, Echo and Guard. Made for ears."], ["Find", "Hold on the list. Pick letters to reach any app."]] as const;

export default function Home() {
  return (
    <Smooth>
      <SceneLayer />
      <LocalNav right={<SoundToggle />} />
      <main className="relative z-10">
        <div id="overview"><Hero /></div>

        <section id="highlights" className={sec}>
          <Reveal><p className={label}>Get the highlights</p><h2 className={h2}>Everything. Without looking.</h2></Reveal>
          <div className="mt-[4vh] grid grid-cols-2 gap-x-6 gap-y-[2.2vh] md:gap-x-10 lg:grid-cols-3">
            {HIGHLIGHTS.map(([t, d]) => (
              <Reveal key={t}><div className="border-t border-line pt-[1.6vh]"><h3 className="text-[clamp(20px,3.2vh,30px)]">{t}</h3><p className="mt-1 font-sans text-[13px] leading-snug text-mute md:text-sm">{d}</p></div></Reveal>
            ))}
          </div>
        </section>

        <section id="screen" className={sec}>
          <div className={two}>
            <Reveal><p className={label}>Take a closer look</p><h2 className={h2}>Turn the screen off.<br /><em className="text-mute">Keep the phone.</em></h2>
              <p className={lead}>The same menu, spoken. Messages, tasks, timer, music, calls. Your phone stays in your pocket.</p></Reveal>
            <ScreenSlider />
          </div>
        </section>

        <section className={sec}>
          <div className={two}>
            <Reveal><p className={label}>It reads. You click.</p><h2 className={h2}>Think quiet.</h2>
              <p className={lead}>The phone speaks the options, one by one. Click on the one you want. This is called auto-scan.</p></Reveal>
            <ScanDemo />
          </div>
        </section>

        <section className={sec}>
          <Reveal><p className={label}>One button, four moves</p><h2 className={h2}>Simple on purpose.</h2></Reveal>
          <div className="mt-[5vh] grid grid-cols-2 gap-x-8 gap-y-[4vh] lg:grid-cols-4">
            {MOVES.map(([t, d, n]) => (
              <Reveal key={t}>
                <div className="flex h-6 items-center gap-2.5">{n ? Array.from({ length: n }, (_, k) => <span key={k} className="block size-5 rounded-full bg-paper" />) : <span className="block h-5 w-24 rounded-full bg-paper" />}</div>
                <h3 className="mb-1.5 mt-[2vh] font-sans text-[13px] font-semibold uppercase tracking-[0.14em]">{t}</h3>
                <p className="font-sans text-sm text-mute">{d}</p>
              </Reveal>
            ))}
          </div>
        </section>

        <section className={sec}>
          <Reveal><p className={label}>All the must-haves</p><h2 className={h2}>All on your ears.</h2></Reveal>
          <div className="mt-[4vh] grid grid-cols-2 gap-x-4 gap-y-[1.6vh] md:gap-x-10 lg:grid-cols-3">
            {APPS.map(([t, d]) => (
              <Reveal key={t}><div className="rounded-2xl border border-line bg-[#070707] p-[2.2vh]"><h3 className="text-[clamp(20px,3.2vh,30px)]">{t}</h3><p className="mt-1 font-sans text-[13px] leading-snug text-mute">{d}</p></div></Reveal>
            ))}
          </div>
        </section>

        <section className={sec}>
          <Reveal><p className={label}>Privacy</p></Reveal>
          {["microphone.", "cloud.", "account.", "screen needed."].map((w) => (
            <Reveal key={w}><p className="text-[clamp(36px,11vh,120px)] leading-[1.04] tracking-[-0.03em]"><em className="text-mute">No</em> {w}</p></Reveal>
          ))}
          <Reveal><p className={lead}>It does not listen. It speaks, and waits for your finger. Everything stays on the phone.</p></Reveal>
        </section>

        <section id="laptop" className={sec}>
          <div className={two}>
            <Reveal><p className={label}>New: Laptop</p><h2 className={h2}>Your agents, in your ear.</h2>
              <p className={lead}>Many windows on your laptop, each with an agent at work. Away from the desk, the earbud tells you which one needs you, reads what it asks, and sends the answer.</p></Reveal>
            <Reveal>
              <div className="rounded-[18px] border border-line bg-[#070707] p-[3vh]">
                <p className="font-sans text-[13px] tracking-wide text-mute">Battery-free device ideas <b className="ml-2 font-semibold text-paper">needs you</b></p>
                <p className="my-[2vh] text-[clamp(18px,3vh,26px)]">“Pick the mini’s cell. Reply use AM-1454, or keep it small.”</p>
                <p className="font-sans text-sm text-mute">Click: <b className="font-semibold text-paper">use AM-1454</b> · Sent.</p>
              </div>
            </Reveal>
          </div>
        </section>

        <section id="faq" className={sec}>
          <div className={two}>
            <Reveal><p className={label}>Questions</p><h2 className={h2}>Open source.<br /><em className="text-mute">Used every day.</em></h2>
              <p className={lead}>Android 10 or newer. Built in Kotlin. MIT license.</p>
              <a href="https://github.com/IFAKA/invisible-os" className="mt-[3vh] inline-block rounded-full bg-paper px-7 py-4 font-sans text-[13px] font-medium uppercase tracking-[0.16em] text-ink transition-transform hover:-translate-y-0.5">Get it on GitHub</a></Reveal>
            <Reveal><Faq /></Reveal>
          </div>
          <p className="absolute inset-x-6 bottom-4 font-sans text-xs text-mute md:inset-x-[8vw]">For people who cannot, or do not want to, look at the phone.</p>
        </section>
      </main>
    </Smooth>
  );
}
