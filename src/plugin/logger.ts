// @ts-nocheck
// Module logger for the antigravity provider. Output goes through core's shared
// logger (makeWriteLog): per-plugin file logging (config/<home>/logs, gated by the
// `logging` flag) + global stderr mirror (the ecosystem-wide logConsole setting) +
// per-plugin coloring, so this provider logs like every other plugin instead of
// hand-rolling its own console/file sinks. `debug`-level lines are additionally
// gated by config.debug (off by default) to avoid flooding the log file.

import { isDebugEnabled } from "./debug";
import { makeWriteLog } from "@intisy-ai/core";

type LogLevel = "debug" | "info" | "warn" | "error";

export interface Logger {
  debug(message: string, extra?: Record<string, unknown>): void;
  info(message: string, extra?: Record<string, unknown>): void;
  warn(message: string, extra?: Record<string, unknown>): void;
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
