"use client";

/** The earbud button, as light: a glowing core and sound rings. The rings scale inside this box, so they are never cut. */
export default function Orb({ burst = 0 }: { burst?: number }) {
  return (
    <div aria-hidden className="absolute inset-0 grid place-items-center">
      <div className="relative aspect-square h-[min(100%,88vw,420px)]">
        <div className="absolute inset-[-12%] rounded-full bg-[radial-gradient(closest-side,rgba(41,151,255,0.28),rgba(41,151,255,0.06)_65%,transparent)]" />
        {[0, 0.9, 1.8].map((d) => <span key={d} className="ring absolute inset-0 rounded-full border border-[#6cb4ff]" style={{ animationDelay: `${d}s` }} />)}
        {burst > 0 && <span key={burst} className="ring-once absolute inset-0 rounded-full border-2 border-white" />}
        <div className="absolute left-1/2 top-1/2 size-[16%] -translate-x-1/2 -translate-y-1/2 rounded-full bg-[radial-gradient(circle_at_35%_30%,#cfe5ff,#2997ff_55%,#0a4fb0)] shadow-[0_0_60px_10px_rgba(41,151,255,0.55)]" />
      </div>
    </div>
  );
}
