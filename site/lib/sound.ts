import { Howl } from "howler";

const make = (f: string) => new Howl({ src: [`/sounds/${f}.wav`], volume: 0.6, preload: true });
let sounds: Record<"tap" | "tick" | "pick" | "sent", Howl> | null = null;
export let soundOn = false;

export function setSound(on: boolean) {
  soundOn = on;
  if (on && !sounds) sounds = { tap: make("tap"), tick: make("tick"), pick: make("pick"), sent: make("sent") };
}
export const play = (n: "tap" | "tick" | "pick" | "sent") => { if (soundOn) sounds?.[n].play(); };
