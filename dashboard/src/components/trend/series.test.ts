import { describe, expect, it } from "vitest";

import { withGaps } from "./series";

const at = (minute: number, mean: number | null = 10) => ({
  capturedAt: new Date(Date.UTC(2026, 8, 26, 12, minute)).toISOString(),
  calls: mean === null ? 0 : 5,
  measuredMeanMs: mean,
  measuredTotalMs: mean === null ? 0 : mean * 5,
});

describe("withGaps", () => {
  it("keeps evenly spaced points joined", () => {
    expect(withGaps([at(0), at(5), at(10)]).map((p) => p.meanMs)).toEqual([10, 10, 10]);
  });

  it("breaks the line where the query didn't run", () => {
    const points = withGaps([at(0), at(5), at(10), at(40), at(45)]);
    expect(points.map((p) => p.meanMs)).toEqual([10, 10, 10, null, 10, 10]);
  });

  it("keeps a null mean as a gap, never as zero", () => {
    expect(withGaps([at(0), at(5, null), at(10)])[1]!.meanMs).toBeNull();
  });
});
