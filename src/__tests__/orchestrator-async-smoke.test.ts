import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

// The smoke drives the TeaVM-compiled orchestrator through genuinely async JS fakes, which needs the
// module the gradle build emits loaded in a plain node process with real timers. Run as a child so
// its own per-scenario report is what a failure shows.
const SMOKE = fileURLToPath(new URL("../../antigravity-teavm/smoke/orchestrator-async-smoke.mjs", import.meta.url));

it("composes every @Async bridge under TeaVM", () => {
  let report: string;
  try {
    report = execFileSync(process.execPath, [SMOKE], { encoding: "utf8", stdio: "pipe" });
  } catch (failure) {
    const run = failure as { stdout?: string; stderr?: string };
    throw new Error(`${run.stdout ?? ""}${run.stderr ?? ""}`);
  }
  expect(report).toContain("all scenarios passed");
}, 120_000);
