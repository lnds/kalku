import { describe, expect, it } from "vitest";
import { above, clamp, total } from "../src/calc";

describe("above", () => {
  it("is true at the floor and below it is false", () => {
    expect(above(3, 3)).toBe(true);
    expect(above(4, 3)).toBe(true);
    expect(above(2, 3)).toBe(false);
  });
});

it("total adds", () => {
  expect(total([1, 2, 3])).toBe(6);
});

// Only well above the boundary, on purpose: this is the hole kalku should find.
it("clamp keeps a positive number", () => {
  expect(clamp(5)).toBe(5);
});

// Two tests with one name, as suites written by copy and paste have.
it("total adds", () => {
  expect(total([2, 2])).toBe(4);
});
