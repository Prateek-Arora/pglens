/**
 * Number and time formatting for every screen. Fixed to en-US and UTC so the server's HTML and the
 * browser's render agree (no hydration mismatch) and every viewer reads the same text.
 */
const grouped = new Intl.NumberFormat("en-US");

/** A duration: "0.42 ms", "12.3 ms", "845 ms", "12.3 s", "4.5 min", "2.1 h". */
export function formatMs(ms: number): string {
  const abs = Math.abs(ms);
  if (abs < 1) return `${trim(ms, 2)} ms`;
  if (abs < 100) return `${trim(ms, 1)} ms`;
  if (abs < 1000) return `${Math.round(ms)} ms`;
  if (abs < 60_000) return `${trim(ms / 1000, abs < 10_000 ? 2 : 1)} s`;
  if (abs < 3_600_000) return `${trim(ms / 60_000, 1)} min`;
  return `${trim(ms / 3_600_000, 1)} h`;
}

/** A whole count, exact: "1,234,567". */
export function formatCount(n: number): string {
  return grouped.format(n);
}

/** A fraction as a whole percentage: 0.873 → "87%". */
export function formatPercent(fraction: number): string {
  return `${Math.round(fraction * 100)}%`;
}

/** A planner cost (the planner's own unit, not time): "1,234.5". */
export function formatCost(cost: number): string {
  return new Intl.NumberFormat("en-US", { maximumFractionDigits: 1 }).format(cost);
}

/** An instant as "2026-09-26 14:05 UTC". */
export function formatUtc(iso: string): string {
  return `${iso.slice(0, 10)} ${iso.slice(11, 16)} UTC`;
}

/** How long ago, coarse: "just now", "4 min ago", "3 h ago", "2 days ago". */
export function formatAgo(iso: string, now: Date): string {
  const seconds = Math.max(0, (now.getTime() - Date.parse(iso)) / 1000);
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)} h ago`;
  const days = Math.floor(seconds / 86_400);
  return `${days} day${days === 1 ? "" : "s"} ago`;
}

function trim(n: number, digits: number): string {
  return String(Number(n.toFixed(digits)));
}
