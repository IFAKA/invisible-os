import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";

const sans = Inter({ variable: "--font-sans", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Invisible OS: your phone, without the screen",
  description: "Control your Android phone with one earbud button, screen off. The phone speaks the options. One click picks. No microphone. No cloud.",
  openGraph: { title: "Invisible OS", description: "One button. Your ears. Nothing else." },
  themeColor: "#000000",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={sans.variable} style={{ colorScheme: "dark" }}>
      <body>{children}</body>
    </html>
  );
}
