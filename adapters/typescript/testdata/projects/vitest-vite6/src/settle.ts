/** Walks n to zero; with its ternary negated it walks away forever, which is a timeout. */
export function settle(n: number): number {
  let i = n;
  while (i !== 0) i = i > 0 ? i - 1 : i + 1;
  return i;
}
