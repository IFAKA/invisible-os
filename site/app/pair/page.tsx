"use client";

import { useSyncExternalStore } from "react";

// The QR code that `invisible pair` shows opens this page: https://invisible-os.vercel.app/pair#CODE
// With the app installed, Android opens the app directly (app link) and this page is never seen.
// Without it: 1. install, 2. connect. The code stays after "#", so it is never sent to this server.
const APK = "https://github.com/IFAKA/invisible-os/releases/latest/download/invisible-os.apk";

function onHash(cb: () => void) {
  window.addEventListener("hashchange", cb);
  return () => window.removeEventListener("hashchange", cb);
}

export default function Pair() {
  const code = useSyncExternalStore(onHash, () => window.location.hash.slice(1), () => "");
  const connect = code
    ? `intent://${code}#Intent;scheme=invisible-pair;package=com.faka.invisible;S.browser_fallback_url=${encodeURIComponent(APK)};end`
    : "";
  return (
    <main className="mx-auto flex min-h-svh max-w-[480px] flex-col justify-center gap-8 px-6 py-12">
      <h1 className="text-[40px] font-semibold leading-tight tracking-tight">Connect your phone</h1>
      {!code && <p className="text-mute">This link has no pair code. On your computer, run <code>invisible pair</code> and scan its QR code.</p>}
      {code && (
        <ol className="flex flex-col gap-6">
          <li className="flex flex-col gap-3">
            <p className="text-[19px]"><span className="text-mute">1.</span> Install Invisible OS</p>
            <a href={APK} className="rounded-full border border-white/20 px-6 py-3 text-center text-[17px] font-medium">Download the app</a>
            <p className="text-sm text-mute">Already installed? Go to step 2.</p>
          </li>
          <li className="flex flex-col gap-3">
            <p className="text-[19px]"><span className="text-mute">2.</span> Connect to your computer</p>
            <a href={connect} className="rounded-full bg-[#0071e3] px-6 py-3 text-center text-[17px] font-medium text-white hover:bg-[#0077ed]">Connect</a>
          </li>
        </ol>
      )}
      <p className="text-sm text-mute">The app never listens: no microphone, no account.</p>
    </main>
  );
}
