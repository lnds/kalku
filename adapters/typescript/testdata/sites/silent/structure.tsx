"use strict";
import { thing } from "./thing";
export { thing };

interface Shape {
  sides: 3;
  name: "tri";
}

type Pair = [1, 2];

enum Color {
  Red = 1,
  Blue = "blue",
}

declare const ambient: number;
declare function external(n: 3): void;

function decorate(_n: number) {
  return (_t: unknown) => undefined;
}

@decorate(9)
class Decorated {}

const lazy = () => import("./lazy");
const dep = require("./dep");

export function view(props: { on: boolean }) {
  const table = { "key": 1, 2: "two", [3 + 4]: 5 };
  const read = table["key"];
  switch (props.on ? 1 : 0) {
    case 1:
      return <div className="box" hidden={props.on && false}>{"text"}</div>;
    default:
      return null;
  }
}

if (import.meta.vitest) {
  const { it, expect } = import.meta.vitest;
  it("works", () => expect(1 + 1).toBe(2));
}

export { Color, Decorated, lazy, dep };
export type { Shape, Pair };
