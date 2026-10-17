export function allowed(admin: boolean, owner: boolean, open: boolean): boolean {
  return (admin || owner) && open;
}

export function fallback(name?: string): string {
  return name ?? "anonymous";
}
