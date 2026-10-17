// Requests are read as they arrive, so that an `abort` is heard while a
// cast is still running; everything else is served in order. The input
// ending is heard here too. After a `shutdown` it is a driver that said all
// it had to say, and every request before it is answered. With none it is a
// run that is gone, and the kalku ends there, with what it started.

import { LineReader } from "./framing";
import * as wire from "./protocol";
import { DecodeError, MAX_LINE, type Request } from "./protocol";
import { endAll } from "./process";
import type { Reply, Service } from "./service";

type Heard = { request: Request } | { reply: string };

export type Ending = "bye" | "gone";

export function serve(
  input: NodeJS.ReadableStream,
  write: (line: string) => Promise<void>,
  service: Service,
): Promise<Ending> {
  return new Promise((resolve, reject) => {
    const reader = new LineReader(MAX_LINE);
    const queue: Heard[] = [];
    let serving = false;
    let shutdown = false;
    let ended = false;
    let running: { id: number; controller: AbortController } | undefined;

    const heard = (lines: ReturnType<LineReader["feed"]>): void => {
      for (const line of lines) {
        if (shutdown) return;
        const h = decoded(line);
        if ("request" in h) {
          if (h.request.type === "abort" && running?.id === h.request.cast) running.controller.abort();
          if (h.request.type === "shutdown") shutdown = true;
        }
        queue.push(h);
      }
      void next();
    };

    const next = async (): Promise<void> => {
      if (serving) return;
      serving = true;
      try {
        for (let h = queue.shift(); h !== undefined; h = queue.shift()) {
          const reply = await answer(h);
          if ("quiet" in reply) continue;
          await write(reply.line);
          if (reply.last) {
            resolve("bye");
            return;
          }
        }
      } catch (e) {
        reject(e);
        return;
      } finally {
        serving = false;
      }
      if (ended && !shutdown) resolve("gone");
    };

    const answer = async (h: Heard): Promise<Reply> => {
      if ("reply" in h) return { line: h.reply };
      const controller = new AbortController();
      running = { id: h.request.id, controller };
      try {
        return await service.handle(h.request, controller.signal);
      } catch (e) {
        return { line: wire.error(h.request.id, "internal_error", (e as Error).stack ?? String(e), true) };
      } finally {
        running = undefined;
      }
    };

    input.on("data", (chunk: Buffer) => heard(reader.feed(chunk)));
    input.on("end", () => {
      ended = true;
      heard(reader.end());
      if (!shutdown) {
        running?.controller.abort();
        endAll();
        resolve("gone");
      }
    });
    input.on("error", () => {
      endAll();
      resolve("gone");
    });
  });
}

function decoded(line: ReturnType<LineReader["feed"]>[number]): Heard {
  if (line.tooLong) {
    return { reply: wire.error(0, "bad_request", `line_too_long: a line longer than ${MAX_LINE} bytes`, false) };
  }
  try {
    return { request: wire.decode(line.text) };
  } catch (e) {
    if (e instanceof DecodeError)
      return { reply: wire.error(e.id ?? 0, "bad_request", `${e.kind}: ${e.detail}`, false) };
    throw e;
  }
}
