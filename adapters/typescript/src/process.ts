// Running a child: the test runner, in a process group of its own, so that
// an abort ends it and everything it started, workers included. Its stdout
// is not the protocol's: both of its streams go to this kalku's stderr.

import { spawn } from "node:child_process";

export class Aborted extends Error {
  constructor() {
    super("aborted");
  }
}

export interface Ran {
  code: number | null;
  signal: string | null;
  stdout: string;
  /** The last lines it wrote on stderr, for saying why it failed. */
  said: string;
}

const running = new Set<number>();

/** Ends every group still running: input ended, and nobody is left to answer. */
export function endAll(): void {
  for (const pid of running) kill(pid);
  running.clear();
}

function kill(pid: number): void {
  try {
    process.kill(-pid, "SIGKILL");
  } catch {
    // Already gone.
  }
}

const KEEP = 40;

/**
 * Runs a command to its end. `keep` holds stdout to return instead of
 * passing it on; a `signal` that fires kills the group and rejects with
 * `Aborted`.
 */
export function run(
  command: string,
  args: string[],
  opts: { cwd: string; env: NodeJS.ProcessEnv; signal?: AbortSignal; keep?: boolean },
): Promise<Ran> {
  return new Promise((resolve, reject) => {
    if (opts.signal?.aborted) {
      reject(new Aborted());
      return;
    }
    const child = spawn(command, args, {
      cwd: opts.cwd,
      env: opts.env,
      detached: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    const pid = child.pid;
    if (pid !== undefined) running.add(pid);
    const out: Buffer[] = [];
    let tail: string[] = [];
    child.stdout.on("data", (chunk: Buffer) => {
      if (opts.keep) out.push(chunk);
      else process.stderr.write(chunk);
    });
    child.stderr.on("data", (chunk: Buffer) => {
      process.stderr.write(chunk);
      tail = tail.concat(chunk.toString("utf8").split("\n")).slice(-KEEP);
    });
    const onAbort = (): void => {
      if (pid !== undefined) kill(pid);
    };
    opts.signal?.addEventListener("abort", onAbort, { once: true });
    child.on("error", (e) => {
      opts.signal?.removeEventListener("abort", onAbort);
      if (pid !== undefined) running.delete(pid);
      reject(e);
    });
    child.on("close", (code, signal) => {
      opts.signal?.removeEventListener("abort", onAbort);
      if (pid !== undefined) {
        // A test runner can leave a worker behind; the group goes with it.
        kill(pid);
        running.delete(pid);
      }
      if (opts.signal?.aborted) {
        reject(new Aborted());
        return;
      }
      resolve({ code, signal, stdout: Buffer.concat(out).toString("utf8"), said: tail.join("\n").trim() });
    });
  });
}
