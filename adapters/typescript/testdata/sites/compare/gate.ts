export function inside(n: number, lo: number, hi: number): boolean {
  return n >= lo && n < hi;
}

export function same(a: unknown, b: unknown): boolean {
  return a === b || a == null;
}

export function grown(a: number, b: number): boolean {
  return a > b || a <= 0 || a !== b || a != b;
}
