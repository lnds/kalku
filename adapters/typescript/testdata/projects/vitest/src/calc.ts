import { clampAt } from "./bounds";

/** Whether n is at or above the floor. */
export function above(n: number, floor: number): boolean {
  return n >= floor;
}

/** The sum of a list. */
export function total(xs: number[]): number {
  let sum = 0;
  for (const x of xs) sum += x;
  return sum;
}

/** Clamped at zero; the boundary is never tested, so a wekufe there lives. */
export function clamp(n: number): number {
  return clampAt(n, 0);
}
