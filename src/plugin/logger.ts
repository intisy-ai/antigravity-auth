// Module logger for the antigravity provider. Output goes through core's shared
// logger (makeWriteLog): per-plugin file logging (config/<home>/logs, gated by the
// `logging` flag) + global stderr mirror (the ecosystem-wide logConsole setting) +
// per-plugin coloring, so this provider logs like every other plugin instead of
// hand-rolling its own console/file sinks. `debug`-level lines are additionally
// gated by config.debug (off by default) to avoid flooding the log file.

import { isDebugEnabled } from "./debug.js";
import { makeWriteLog } from "@intisy-ai/basekit";

type LogLevel = "debug" | "info" | "warn" | "error";

/** Where one part of this provider writes its diagnostics, tagged with its own name. */
export interface Logger {
  /**
   * A line only a debug run keeps.
   *
   * @param message - what happened
   * @param extra - structured detail to log beside it
   */
  debug(message: string, extra?: Record<string, unknown>): void;
  /**
   * A line worth keeping on an ordinary run.
   *
   * @param message - what happened
   * @param extra - structured detail to log beside it
   */
  info(message: string, extra?: Record<string, unknown>): void;
  /**
   * Something went wrong that the run recovered from.
   *
   * @param message - what happened
   * @param extra - structured detail to log beside it
   */
  warn(message: string, extra?: Record<string, unknown>): void;
  /**
   * Something went wrong that the run did not recover from.
   *
   * @param message - what happened
   * @param extra - structured detail to log beside it
   */
  error(message: string, extra?: Record<string, unknown>): void;
}

const writeLog = makeWriteLog("antigravity");

function safeJson(value: unknown): string {
  try { return JSON.stringify(value); } catch { return String(value); }
}

/** Create a logger for a module (e.g. "request", "transform.claude"). */
export function createLogger(module: string): Logger {
  const emit = (level: LogLevel, message: string, extra?: Record<string, unknown>): void => {
    // debug level is verbose; only persist it when file debug is enabled.
    if (level === "debug" && !isDebugEnabled()) return;
    const suffix = extra ? " " + safeJson(extra) : "";
    writeLog(`[${level}] ${module}: ${message}${suffix}`, level === "error");
  };

  return {
    debug: (message, extra) => emit("debug", message, extra),
    info: (message, extra) => emit("info", message, extra),
    warn: (message, extra) => emit("warn", message, extra),
    error: (message, extra) => emit("error", message, extra),
  };
}
