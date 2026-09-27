import type { Metadata } from "next";
import localFont from "next/font/local";
import { connection } from "next/server";

import { TooltipProvider } from "@/components/ui/tooltip";
import "./globals.css";

// Fonts from npm, bundled at build time: no request to a font CDN, at build or at run time.
const plexSans = localFont({
  src: "../../node_modules/@fontsource-variable/ibm-plex-sans/files/ibm-plex-sans-latin-wght-normal.woff2",
  weight: "100 700",
  variable: "--font-plex-sans",
  display: "swap",
});
// JetBrains Mono for SQL: a tall x-height and clear punctuation keep long queries readable at 13 px.
const codeMono = localFont({
  src: "../../node_modules/@fontsource-variable/jetbrains-mono/files/jetbrains-mono-latin-wght-normal.woff2",
  weight: "100 800",
  variable: "--font-code-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: { template: "%s · PgLens", default: "PgLens" },
  description: "Postgres slow-query and index advisor",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  // Every page renders per request: the CSP nonce (src/proxy.ts) can't be stamped into a page
  // prerendered at build time, and no page here is the same for every user anyway.
  await connection();
  return (
    <html lang="en" className={`${plexSans.variable} ${codeMono.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">
        <TooltipProvider>{children}</TooltipProvider>
      </body>
    </html>
  );
}
