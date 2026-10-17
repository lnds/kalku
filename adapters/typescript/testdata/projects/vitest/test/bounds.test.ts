import { expect, test } from "vitest";
import { wrapped } from "../src/bounds";

test("wrapping stays inside the range", () => {
  expect(wrapped(12)).toBe(2);
  expect(wrapped(7)).toBe(7);
});
