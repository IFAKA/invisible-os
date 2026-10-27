import { Code2, Laptop, MessageCircle, MicOff, MousePointerClick, Volume2 } from "lucide-react";
import Smooth from "@/components/Smooth";
import LocalNav from "@/components/LocalNav";
import Hero from "@/components/Hero";
import LaptopDemo from "@/components/LaptopDemo";
import Story from "@/components/Story";
import Privacy from "@/components/Privacy";
import Faq from "@/components/Faq";
import Reveal from "@/components/Reveal";

// Every normal section is exactly one screen tall, centered, one idea (like Apple). Sizes follow the screen height (svh).
const sec = "mx-auto flex h-svh max-w-[1080px] flex-col items-center justify-center px-5 pb-8 pt-16 text-center";
const h2 = "text-[clamp(32px,7svh,64px)] font-semibold leading-[1.06] tracking-[-0.035em] [text-wrap:balance]";
const eyebrow = "mb-[1.4svh] text-[clamp(17px,2.6svh,22px)] font-semibold";

const HIGHLIGHTS = [
  [MousePointerClick, "One button", "Click, two, three, hold. That is all you need."],
  [Volume2, "It reads to you", "Options are spoken one by one. Click on the one you want."],
  [MicOff, "No microphone", "It never listens. No cloud, no account."],
  [MessageCircle, "Your messages", "Hear WhatsApp, Teams and WeChat. Reply by click."],
  [Laptop, "Your laptop", "Know which agent needs you. Answer from the earbud."],
  [Code2, "Open source", "Android, Kotlin, MIT. Make your own program for it."],
] as const;

const SPECS = [
  ["Phone", "Android 10 or newer"],
  ["Input", "Any earbud or headset with one button"],
  ["Menu", "English. Messages are read in their own language."],
  ["Privacy", "No microphone permission. No cloud. No account."],
  ["Laptop", "tmux and Claude Code, over your own network"],
  ["License", "MIT, open source"],
] as const;

export default function Home() {
  return (
    <Smooth>
      <LocalNav />
      <main>
        <Hero />
        <Story />

        <section id="highlights" className={sec}>
          <Reveal><h2 className={h2}>Get the highlights.</h2></Reveal>
          <div className="mt-[4svh] grid w-full grid-cols-2 gap-3 md:grid-cols-3 md:gap-4">
            {HIGHLIGHTS.map(([Icon, t, d], i) => (
              <Reveal key={t} delay={i * 0.06}>
                <div className="h-full rounded-[22px] bg-card p-[clamp(14px,2.6svh,28px)] text-left">
                  <Icon className="size-[clamp(22px,3.6svh,32px)] text-blue" strokeWidth={1.8} />
                  <h3 className="mt-[1.6svh] text-[clamp(17px,2.8svh,24px)] font-semibold tracking-[-0.02em]">{t}</h3>
                  <p className="mt-1 text-[clamp(13px,1.9svh,16px)] leading-snug text-mute">{d}</p>
                </div>
              </Reveal>
            ))}
          </div>
        </section>

        <Privacy />

        <LaptopDemo />

        <section id="specs" className={sec}>
          <Reveal><h2 className={h2}>Tech specs.</h2></Reveal>
          <Reveal delay={0.1} className="w-full max-w-[720px]">
            <dl className="mt-[4svh] divide-y divide-line border-y border-line text-left">
              {SPECS.map(([k, v]) => (
                <div key={k} className="grid grid-cols-[7em_1fr] gap-4 py-[1.5svh] text-[clamp(14px,2.1svh,18px)]"><dt className="text-mute">{k}</dt><dd className="font-medium">{v}</dd></div>
              ))}
            </dl>
          </Reveal>
        </section>

        <section id="faq" className={sec}>
          <Reveal><h2 className={h2}>Open source.<br />Used every day.</h2></Reveal>
          <Reveal delay={0.1}><a href="https://github.com/IFAKA/invisible-os" className="mt-[3svh] inline-block rounded-full bg-[#0071e3] px-6 py-3 text-[17px] font-medium text-white hover:bg-[#0077ed]">Get it on GitHub</a></Reveal>
          <Reveal delay={0.2} className="mt-[4svh] w-full max-w-[680px] text-left"><Faq /></Reveal>
          <p className="mt-[3svh] text-xs text-mute">For people who cannot, or do not want to, look at the phone.</p>
        </section>
      </main>
    </Smooth>
  );
}
