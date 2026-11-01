import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy policy: Invisible OS",
  description: "What Invisible OS reads on your phone, what leaves it, and how to delete it.",
};

const REPO = "https://github.com/IFAKA/invisible-os";

const READS = [
  ["Notifications", "To read them to you, and to let you reply or dismiss them. Private ones (bank, password, locked) are read only as \"new notification\"."],
  ["Contacts", "To say who is calling and to let you call a contact."],
  ["Calendar", "To tell you your next event."],
  ["Location", "Optional. For the weather and \"Where am I\"."],
  ["Call state and call log", "To announce calls and to put the people you call most first."],
  ["App usage", "Optional. To order the menu by what you use."],
  ["Accessibility key events", "To see your earbud button presses while the screen is off. Only the button keys are used."],
  ["The Write text field", "The text you type with the Write keyboard, to send it where you choose."],
] as const;

const LEAVES = [
  ["AI model and voice", "Downloaded once, only when you ask, from huggingface.co and github.com. Only a file request is sent."],
  ["Weather", "Your coordinates, rounded to 2 decimals, go to api.open-meteo.com."],
  ["Music", "The song or artist you ask for goes to music.youtube.com."],
  ["Your laptop", "Optional. Only if you pair a computer. It talks to your own computer on your own network."],
  ["Problem report", "Only if you tap \"Send a problem report\". It makes a text file of the last 2 days of the log, and you choose where to share it."],
] as const;

export default function PrivacyPolicy() {
  return (
    <main className="mx-auto flex min-h-svh max-w-[680px] flex-col gap-10 px-6 py-16">
      <header className="flex flex-col gap-3">
        <h1 className="text-[40px] font-semibold leading-tight tracking-tight">Privacy policy</h1>
        <p className="text-mute">Invisible OS, the Android app. Last updated 1 November 2026.</p>
        <p className="text-[19px] leading-snug">The app has no microphone permission and never listens. It has no account, no analytics and no ads. Nothing is sold or shared.</p>
      </header>

      <section className="flex flex-col gap-3">
        <h2 className="text-[24px] font-semibold tracking-tight">What the app reads on your phone</h2>
        <dl className="divide-y divide-line border-y border-line text-left">
          {READS.map(([k, v]) => (
            <div key={k} className="grid grid-cols-1 gap-1 py-3 sm:grid-cols-[12em_1fr] sm:gap-4"><dt className="font-medium">{k}</dt><dd className="text-mute">{v}</dd></div>
          ))}
        </dl>
        <p className="text-mute">All of this is used on the phone. It is not uploaded by the app. Learning (which options you pick) is stored on the phone only.</p>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-[24px] font-semibold tracking-tight">What leaves the phone</h2>
        <dl className="divide-y divide-line border-y border-line text-left">
          {LEAVES.map(([k, v]) => (
            <div key={k} className="grid grid-cols-1 gap-1 py-3 sm:grid-cols-[12em_1fr] sm:gap-4"><dt className="font-medium">{k}</dt><dd className="text-mute">{v}</dd></div>
          ))}
        </dl>
        <p className="text-mute">Nothing else. The log of what you pressed and what the phone said stays on the phone for 7 days.</p>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-[24px] font-semibold tracking-tight">Delete your data</h2>
        <p className="text-mute">Uninstall the app, or open Android Settings, Apps, Invisible OS, Storage, Clear data. Everything the app keeps is on the phone, so this removes it all.</p>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-[24px] font-semibold tracking-tight">Contact</h2>
        <p className="text-mute">Open an issue on <a className="underline" href={`${REPO}/issues`}>{REPO}/issues</a>. The code is open source, so you can check all of the above.</p>
      </section>

      <a href="/" className="text-sm text-mute underline">Back to the home page</a>
    </main>
  );
}
