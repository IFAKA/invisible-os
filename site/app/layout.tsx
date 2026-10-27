import type { Metadata } from "next";
import { Instrument_Serif, Inter } from "next/font/google";
import "./globals.css";

const serif = Instrument_Serif({ variable: "--font-serif", subsets: ["latin"], weight: "400", style: ["normal", "italic"] });
const sans = Inter({ variable: "--font-sans", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Invisible OS: your phone, without the screen",
  description: "Control your Android phone with one earbud button, screen off. The phone speaks the options. One click picks. No microphone. No cloud.",
  openGraph: { title: "Invisible OS", description: "One button. Your ears. Nothing else." },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${serif.variable} ${sans.variable}`}>
      <body>{children}</body>
    </html>
  );
}
