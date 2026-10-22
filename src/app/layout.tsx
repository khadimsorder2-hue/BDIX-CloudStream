import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "SkStream",
  description: "SkStream — web player for MLSBD & SouthFreak with a pixel-copy CloudStream UI, premium player and floating source selector.",
  icons: { icon: "/player/favicon.svg" },
};

export const viewport: Viewport = {
  themeColor: "#111111",
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className="antialiased" style={{ background: "#111111", margin: 0 }}>
        {children}
      </body>
    </html>
  );
}
