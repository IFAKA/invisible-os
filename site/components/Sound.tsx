"use client";
import { useState } from "react";
import { play, setSound, soundOn } from "@/lib/sound";

export default function SoundToggle() {
  const [on, setOn] = useState(false);
  return (
    <button aria-pressed={on} onClick={() => { setSound(!soundOn); setOn(soundOn); play("tap"); }}
      className="text-xs text-mute hover:text-paper aria-pressed:text-paper">
      {on ? "Sound on" : "Sound off"}
    </button>
  );
}
