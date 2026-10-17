// What a run of the tests said, read from the JSON report Jest writes and
// Vitest writes in the same shape. Each test's own status is read, never a
// file's or the run's summary.

import { readFileSync } from "node:fs";

export type Status = "passed" | "failed" | "skipped";

export interface TestResult {
  /** The test's titles, outermost `describe` first. */
  names: string[];
  status: Status;
  durationMs: number;
  failure: string;
}

export interface FileResult {
  /** Absolute path of the test file. */
  file: string;
  /** Set when the file did not run at all: it failed to load, or its setup threw. */
  broken?: string;
  tests: TestResult[];
}

interface JsonAssertion {
  ancestorTitles?: string[];
  title?: string;
  status?: string;
  duration?: number | null;
  failureMessages?: string[];
}

interface JsonFile {
  name?: string;
  status?: string;
  message?: string;
  assertionResults?: JsonAssertion[];
}

// The colours a runner paints its messages with mean nothing in a report.
const ANSI = /\u001b\[[0-9;]*[A-Za-z]/g;

export function plain(text: string): string {
  return text.replace(ANSI, "");
}

function statusOf(s: string | undefined): Status {
  if (s === "passed") return "passed";
  if (s === "failed") return "failed";
  return "skipped";
}

/** Reads a report, or throws when there is none to read. */
export function readReport(path: string): FileResult[] {
  const json = JSON.parse(readFileSync(path, "utf8")) as { testResults?: JsonFile[] };
  return (json.testResults ?? []).map((f) => {
    const tests = (f.assertionResults ?? []).map((a) => ({
      names: [...(a.ancestorTitles ?? []), a.title ?? ""],
      status: statusOf(a.status),
      durationMs: Math.round(a.duration ?? 0),
      failure: plain((a.failureMessages ?? []).join("\n")).trim(),
    }));
    const result: FileResult = { file: f.name ?? "", tests };
    const message = plain(f.message ?? "").trim();
    if (f.status === "failed" && tests.every((t) => t.status !== "failed") && message !== "") {
      result.broken = message;
    }
    return result;
  });
}
