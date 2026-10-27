// What each function's `return` is offered, by what it declares.

export function count(xs: number[]): number {
  return xs.length;
}

export function countFromZero(): number {
  return 0;
}

export function big(n: bigint): bigint {
  return n * 2n;
}

export function label(n: number): string {
  if (n > 9) return "many";
  return "";
}

export function positive(n: number): boolean {
  return n > 0;
}

export function alwaysYes(): boolean {
  return true;
}

export function names(xs: readonly string[]): readonly string[] {
  return xs.filter((x) => x.length > 0);
}

export function emptyList(): Array<number> {
  return [];
}

export async function fetchCount(load: () => Promise<number>): Promise<number> {
  return await load();
}

export function untyped(a, b) {
  return a + b;
}

export function maybe(n: number): number | undefined {
  if (n < 0) return undefined;
  if (n > 9) return null;
  return n;
}

export function nothing(): void {
  return;
}

export const double = (n: number): number => n * 2;

export const doubled = (xs: number[]) => xs.map((x) => x * 2);

export const notify = (log: (s: string) => void) => log("done");

export const store = (box: { v: number }) => (box.v = 1);

export class Counter {
  private n = 0;

  constructor(start: number) {
    this.n = start;
    return;
  }

  get value(): number {
    return this.n;
  }

  set value(v: number) {
    this.n = v;
  }

  *steps(): Generator<number, string> {
    yield this.n;
    return "end";
  }

  next(): Counter {
    return new Counter(this.n + 1);
  }
}
