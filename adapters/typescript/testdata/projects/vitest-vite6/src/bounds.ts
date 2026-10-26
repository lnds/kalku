export function clampAt(n: number, floor: number): number {
  return n > floor ? n : floor;
}

/** Wrapped into a range; a wekufe that loops forever is a timeout. */
export function wrapped(n: number): number {
  let i = n;
  while (i >= 10) i -= 10;
  return i;
}
