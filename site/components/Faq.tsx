const QA = [
  ["Does it listen to me?", "No. There is no microphone permission and no voice recognition. It speaks, and waits for your finger."],
  ["What earbuds work?", "Any wired or Bluetooth earbud or headset that has one button."],
  ["Which phones?", "Android 10 or newer. Some brands (Xiaomi, for one) need background use allowed."],
  ["Does it need internet?", "No. Only the optional on-phone AI model is downloaded, and only if you ask."],
  ["Is it for blind people only?", "No. It is for anyone who cannot or does not want to look: cooks, runners, riders, people with motor limits."],
] as const;

export default function Faq() {
  return (
    <div className="w-full max-w-[720px] divide-y divide-line border-y border-line font-sans">
      {QA.map(([q, a]) => (
        <details key={q} className="group py-[1.4vh]">
          <summary className="flex cursor-pointer list-none items-center justify-between py-1.5 text-[15px] text-paper">{q}<span className="text-mute transition-transform group-open:rotate-45">+</span></summary>
          <p className="pb-2 pt-1 text-sm leading-relaxed text-mute">{a}</p>
        </details>
      ))}
    </div>
  );
}
