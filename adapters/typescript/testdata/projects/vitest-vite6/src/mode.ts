export type Mode = "fast" | "slow";

/** A wekufe that empties a string here no longer fits the type. */
export function mode(fast: boolean): Mode {
  return fast ? "fast" : "slow";
}
