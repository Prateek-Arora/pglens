import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { dbPoints, MeasuredChange, overviewPoints, Sparkline } from "./impact";

describe("chart points", () => {
  it("draws an hour with no data as a gap, never as zero", () => {
    const points = dbPoints([
      {
        hour: "2026-09-27T08:00:00Z",
        sampled: true,
        measuredTotalMs: 120,
        measuredMsWithAdvice: 80,
        estimatedMsSaved: 40,
        measuredMsBySeries: [],
      },
      {
        hour: "2026-09-27T09:00:00Z",
        sampled: false,
        measuredTotalMs: 0,
        measuredMsWithAdvice: 0,
        estimatedMsSaved: 0,
        measuredMsBySeries: [],
      },
    ]);
    expect(points[0]).toMatchObject({ totalMs: 120, withAdviceMs: 80, savedMs: 40 });
    expect(points[1]).toMatchObject({ totalMs: null, withAdviceMs: null, savedMs: null });
  });

  it("keeps the overview's null hours null", () => {
    const [p] = overviewPoints([
      {
        hour: "2026-09-27T08:00:00Z",
        measuredTotalMs: null,
        measuredMsWithAdvice: null,
        estimatedMsSaved: null,
      },
    ]);
    expect(p).toMatchObject({ t: Date.parse("2026-09-27T08:00:00Z"), totalMs: null });
  });
});

describe("MeasuredChange", () => {
  it("says faster or slower in words, and calls a small change no clear change", () => {
    const { rerender } = render(<MeasuredChange change={-0.457} />);
    expect(screen.getByText(/−46% faster/)).toBeTruthy();
    rerender(<MeasuredChange change={0.2} />);
    expect(screen.getByText(/\+20% slower/)).toBeTruthy();
    rerender(<MeasuredChange change={0.07} />);
    expect(screen.getByText(/no clear change \(\+7%\)/)).toBeTruthy();
  });
});

describe("Sparkline", () => {
  it("breaks the line where an hour has no data", () => {
    const { container } = render(<Sparkline values={[1, 2, null, 3, 4]} label="q" />);
    expect(container.querySelectorAll("path")).toHaveLength(2);
    expect(screen.getByRole("img", { name: "q" })).toBeTruthy();
  });
});
