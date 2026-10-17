// kalku-typescript: speaks the kalku protocol on stdin and stdout. Nothing
// else is written to stdout; what the test runners say goes to stderr.

import { serve } from "./serve";
import { Service } from "./service";

function write(line: string): Promise<void> {
  return new Promise((resolve, reject) => {
    process.stdout.write(`${line}\n`, (e) => (e ? reject(e) : resolve()));
  });
}

serve(process.stdin, write, new Service()).then(
  (ending) => process.exit(ending === "bye" ? 0 : 1),
  (e: unknown) => {
    process.stderr.write(`kalku-typescript: ${(e as Error).stack ?? String(e)}\n`);
    process.exit(1);
  },
);
