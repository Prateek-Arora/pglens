import { describe, expect, it } from "vitest";

import { safeNext } from "./paths";

describe("safeNext", () => {
  it("keeps a local path with its query", () => {
    expect(safeNext("/db/shop?window=24h")).toBe("/db/shop?window=24h");
  });

  it.each([
    ["protocol-relative", "//evil.example/x"],
    ["backslash trick", "/\\evil.example"],
    ["absolute URL", "https://evil.example"],
    ["relative path", "db/shop"],
    ["control character", "/x\n/y"],
    ["not a string", ["/a", "/b"]],
    ["missing", undefined],
  ])("refuses a %s", (_, value) => {
    expect(safeNext(value)).toBe("/");
  });
});
