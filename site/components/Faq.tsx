const QA = [
  ["Does it listen to me?", "No. There is no microphone permission and no voice recognition. It speaks, and waits for your finger."],
  ["What earbuds work?", "Any wired or Bluetooth earbud or headset that has one button."],
  ["Does it need internet?", "Not for the button and the menu. The internet is used only to download the AI model and voice when you ask, for the weather (your rounded coordinates go to open-meteo.com), to search music on YouTube Music, and for your own laptop if you pair one. See the privacy page."],
  ["Is it for blind people only?", "No. It is for anyone who cannot or does not want to look: cooks, runners, riders, people with motor limits."],
] as const;

export default function Faq() {
  return (
    <div className="w-full divide-y divide-line border-y border-line">
      {QA.map(([q, a]) => (
        <details key={q} className="group py-3">
          <summary className="flex cursor-pointer list-none items-center justify-between text-[17px] font-medium">{q}<span className="text-mute transition-transform duration-200 group-open:rotate-45">+</span></summary>
          <p className="pt-2 text-[15px] leading-snug text-mute">{a}</p>
        </details>
      ))}
    </div>
  );
}
