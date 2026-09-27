import { describe, expect, it } from "vitest";

import { formatAgo, formatCount, formatMs, formatPercent, formatUtc } from "./format";

describe("formatMs", () => {
  it.each([
    [0.4234, "0.42 ms"],
    [12.34, "12.3 ms"],
    [845.4, "845 ms"],
    [1234, "1.23 s"],
    [12_345, "12.3 s"],
    [270_000, "4.5 min"],
    [7_560_000, "2.1 h"],
  ])("%s ms → %s", (ms, text) => {
    expect(formatMs(ms)).toBe(text);
  });
});

describe("other formats", () => {
  it("keeps counts exact", () => expect(formatCount(1234567)).toBe("1,234,567"));
  it("rounds a fraction to a whole percentage", () => expect(formatPercent(0.873)).toBe("87%"));
  it("prints instants in UTC", () =>
    expect(formatUtc("2026-09-26T14:05:59Z")).toBe("2026-09-26 14:05 UTC"));
  it("says how long ago, coarsely", () => {
    const now = new Date("2026-09-26T12:00:00Z");
    expect(formatAgo("2026-09-26T11:59:30Z", now)).toBe("just now");
    expect(formatAgo("2026-09-26T11:56:00Z", now)).toBe("4 min ago");
    expect(formatAgo("2026-09-26T09:00:00Z", now)).toBe("3 h ago");
    expect(formatAgo("2026-09-25T12:00:00Z", now)).toBe("1 day ago");
  });
});
