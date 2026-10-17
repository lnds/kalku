export function first(xs: number[]): number {
  if (xs.length === 0) {
    throw new Error("empty");
  }
  let i = 0;
  while (xs[i] < 0) i++;
  for (let j = 0; j < 3; j++) {
    do {
      i--;
    } while (i > 10);
  }
  if (!xs.includes(i)) return -1;
  return i > 2 ? xs[i] : xs[0];
}
