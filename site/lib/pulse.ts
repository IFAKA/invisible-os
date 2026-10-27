/** Shared by the page and the 3D scene: when the last click happened (seconds, performance clock). */
export const pulse = { at: -100, count: 0 };
export const hit = () => { pulse.at = performance.now() / 1000; pulse.count++; };
/** Scroll progress (0 to 1) of the story scene. The page writes it, the 3D earbud reads it. */
export const story = { p: 0 };
