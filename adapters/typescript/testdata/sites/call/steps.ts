import { log } from "./log";

class Base {
  constructor(readonly n: number) {}
}

export class Counter extends Base {
  items: number[] = [];

  constructor(n: number) {
    super(n);
    this.reset();
  }

  reset(): void {
    this.items.length = 0;
  }

  async push(x: number): Promise<void> {
    await this.save(x);
    this.items.push(x);
    log.debug("pushed", x);
    console.log("pushed");
  }

  private async save(_x: number): Promise<void> {}
}

export function guarded(flag: boolean, items: number[]): void {
  if (flag) items.pop();
  else items.shift();
}
