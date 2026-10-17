export function top(): number {
  return 1;
}

export const arrow = (x: number): number => x + 1;

export class Shape {
  #side = 2;

  constructor(private readonly n: number) {
    this.n = n > 0 ? n : 1;
  }

  get area(): number {
    return this.#side * 2;
  }

  scale(k: number): number {
    return [1, 2].map((v) => v * k)[0] ?? 3;
  }

  static make = function named(): number {
    return 4;
  };
}

export namespace Geometry {
  export function origin(): number {
    return 5;
  }
}

export const handlers = {
  onClick: () => 6,
  onKey(code: number): boolean {
    return code === 7;
  },
};

export default function (): number {
  return 8;
}
