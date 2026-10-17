const LIMIT = 10;
const HEX = 0x1f;
const BIG = 1_000;
const RATE = 0.5;
const LARGE = 9007199254740993;
const BIGINT = 10n;

export function start(enabled = true): { count: number; label: string; done: boolean } {
  const label = 'ready';
  const empty = "";
  const tpl = `no ${label} here`;
  return { count: 0 + LIMIT + HEX + BIG + RATE + LARGE + Number(BIGINT), label: label + empty + tpl, done: !enabled || false };
}

export function kind(x: unknown): string {
  return typeof x === "string" ? "text" : typeof x !== 'number' ? "other" : "number";
}
