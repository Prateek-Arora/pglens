import { fireEvent, render, screen } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, expect, it } from "vitest";

import { TooltipProvider } from "@/components/ui/tooltip";
import { Count, Estimate, KIND_LABEL, Measured } from "./numbers";

function show(ui: ReactElement) {
  const { container } = render(<TooltipProvider>{ui}</TooltipProvider>);
  const number = container.querySelector("[data-kind]") as HTMLElement;
  fireEvent.pointerMove(number, { pointerType: "mouse" });
  return number;
}

describe("honesty components", () => {
  it("labels a measured time as measured, in text, tooltip and markup", async () => {
    const n = show(<Measured ms={1234} what="Mean time per call" />);
    expect(n.dataset.kind).toBe("measured");
    expect(n).toHaveProperty("textContent", "1.23 s (measured)");
    expect(await screen.findByRole("tooltip")).toHaveProperty(
      "textContent",
      `Mean time per call. ${KIND_LABEL.measured}`,
    );
  });

  it("says why a measurement is missing instead of showing zero", async () => {
    const n = show(<Measured ms={null} />);
    expect(n.textContent).toBe("— (measured)");
    expect((await screen.findByRole("tooltip")).textContent).toBe(
      "No calls in this window, so nothing was measured.",
    );
  });

  it("marks every estimate visibly, not only in the tooltip", async () => {
    const n = show(<Estimate value={1500} unit="ms" what="Estimated time saved" />);
    expect(n.dataset.kind).toBe("estimate");
    expect(n.textContent).toBe("≈ 1.5 sest. (planner estimate)");
    expect((await screen.findByRole("tooltip")).textContent).toContain("not a measured runtime");
  });

  it("shows a planner cost drop as a reduction, labeled an estimate", () => {
    const n = show(<Estimate value={0.873} unit="drop" what="Planner cost drop" />);
    expect(n.textContent).toBe("−87%est. (planner estimate)");
  });

  it("labels counts as counted", async () => {
    const n = show(<Count value={1234567} what="Calls" />);
    expect(n.dataset.kind).toBe("count");
    expect(n.textContent).toBe("1,234,567 (counted)");
    expect((await screen.findByRole("tooltip")).textContent).toBe(`Calls. ${KIND_LABEL.count}`);
  });
});
