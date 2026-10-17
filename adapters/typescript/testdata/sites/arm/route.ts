export function route(kind: string): number {
  switch (kind) {
    case "a":
    case "b":
      return 1;
    case "c":
      return 2;
    default:
      return 0;
  }
}

export function last(kind: number): string {
  switch (kind) {
    case 1:
      return "one";
    case 2:
      return "two";
  }
  return "many";
}
