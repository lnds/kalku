// Positions in one file. The TypeScript parser counts in UTF-16 code units,
// as JavaScript strings do; the protocol counts bytes of UTF-8 and columns
// in characters. Everything that crosses between them goes through here.

import type { Position } from "./protocol";

export class Source {
  /** The UTF-16 index each line starts at. */
  private readonly starts: number[] = [0];
  /** The byte offset each line starts at. */
  private readonly byteStarts: number[] = [0];

  constructor(readonly text: string) {
    let bytes = 0;
    let from = 0;
    for (let i = 0; i < text.length; i++) {
      if (text.charCodeAt(i) === 0x0a) {
        bytes += Buffer.byteLength(text.slice(from, i + 1), "utf8");
        from = i + 1;
        this.starts.push(from);
        this.byteStarts.push(bytes);
      }
    }
  }

  /** The 0-based line an index is on. */
  lineOf(index: number): number {
    let lo = 0;
    let hi = this.starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (this.starts[mid]! <= index) lo = mid;
      else hi = mid - 1;
    }
    return lo;
  }

  /** The protocol's position of a UTF-16 index. */
  position(index: number): Position {
    const line = this.lineOf(index);
    const before = this.text.slice(this.starts[line]!, index);
    return {
      line: line + 1,
      col: [...before].length + 1,
      byte: this.byteStarts[line]! + Buffer.byteLength(before, "utf8"),
    };
  }
}

/** Puts `replacement` where the bytes [from, to) were. */
export function splice(text: Buffer, from: number, to: number, replacement: string): Buffer {
  return Buffer.concat([text.subarray(0, from), Buffer.from(replacement, "utf8"), text.subarray(to)]);
}
