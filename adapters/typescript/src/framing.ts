// Lines off the channel. A line longer than the protocol allows is
// discarded as it arrives, never held whole, so one oversized message costs
// only itself and the line after it is read.

export type Line = { text: string; tooLong: false } | { tooLong: true };

/** Splits a byte stream into lines of at most `max` bytes, fed chunk by chunk. */
export class LineReader {
  private parts: Buffer[] = [];
  private size = 0;
  private tooLong = false;

  constructor(private readonly max: number) {}

  /** The lines a chunk completes. */
  feed(chunk: Buffer): Line[] {
    const out: Line[] = [];
    let from = 0;
    for (;;) {
      const nl = chunk.indexOf(0x0a, from);
      const piece = nl === -1 ? chunk.subarray(from) : chunk.subarray(from, nl);
      this.take(piece);
      if (nl === -1) return out;
      out.push(this.finish());
      from = nl + 1;
    }
  }

  /** What is left when the input ends: a last line with no newline, if any. */
  end(): Line[] {
    if (this.tooLong) return [this.finish()];
    if (this.size === 0) return [];
    return [this.finish()];
  }

  private take(piece: Buffer): void {
    if (this.tooLong || piece.length === 0) return;
    if (this.size + piece.length > this.max) {
      this.tooLong = true;
      this.parts = [];
      this.size = 0;
      return;
    }
    this.parts.push(piece);
    this.size += piece.length;
  }

  private finish(): Line {
    const line: Line = this.tooLong
      ? { tooLong: true }
      : { text: Buffer.concat(this.parts, this.size).toString("utf8"), tooLong: false };
    this.parts = [];
    this.size = 0;
    this.tooLong = false;
    return line;
  }
}
