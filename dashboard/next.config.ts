import type { NextConfig } from "next";

// Headers every response gets. The Content-Security-Policy is per request (it carries a nonce), so
// it is set in src/proxy.ts. HSTS is left to the TLS-terminating proxy (the README's HTTPS recipe):
// sent from here it would also pin plain-HTTP localhost installs.
const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  {
    key: "Permissions-Policy",
    value: "camera=(), microphone=(), geolocation=(), browsing-topics=()",
  },
  { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
];

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  typedRoutes: true,
  // Next's generated AGENTS.md/CLAUDE.md would duplicate .claude/rules/dashboard.md (ADR-0046).
  agentRules: false,
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
