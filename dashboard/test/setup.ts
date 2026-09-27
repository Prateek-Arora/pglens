import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// jsdom has no layout engine; Radix measures popovers with ResizeObserver.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
};

afterEach(cleanup);
