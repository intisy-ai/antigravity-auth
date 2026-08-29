import { createWriteStream, mkdirSync, readdirSync, statSync, unlinkSync } from "node:fs";
import { join } from "node:path";
import { env } from "node:process";
import type { AntigravityConfig } from "./config";
import {
  deriveDebugPolicy,
  isTruthyFlag,
} from "./logging-utils";
import { ensureGitignoreSync } from "./storage";
import { getAppConfigDir } from "@intisy-ai/basekit";

interface DebugState {
  debugEnabled: boolean;
  debugTuiEnabled: boolean;
  logFilePath: string | undefined;
  logWriter: (line: string) => void;
}

let debugState: DebugState | null = null;

// App-aware (respects HUB_CONFIG_DIR / the active app's registry entry, opencode or Claude), unlike
// a hardcoded ~/.opencode path: this file's debug log otherwise silently landed in the opencode home
// even when running under Claude Code.
function getConfigDir(): string {
  return getAppConfigDir();
}

/**
 * Returns the logs directory, creating it if needed.
 */
function getLogsDir(customLogDir?: string): string {
  const logsDir = customLogDir || join(getConfigDir(), "antigravity-logs");

  try {
    mkdirSync(logsDir, { recursive: true });
  } catch {

  }

  return logsDir;
}

function createLogFilePath(customLogDir?: string): string {
  const logsDir = getLogsDir(customLogDir);
  cleanupOldLogs(logsDir, 25);
  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  return join(logsDir, `antigravity-debug-${timestamp}.log`);
}

/**
 * Cleans up old log files, keeping only the most recent maxFiles.
 */
function cleanupOldLogs(logsDir: string, maxFiles: number): void {
  try {
    const files = readdirSync(logsDir)
      .filter((file) => file.startsWith("antigravity-debug-") && file.endsWith(".log"))
      .map((file) => join(logsDir, file));

    if (files.length <= maxFiles) {
      return;
    }

    const sortedFiles = files
      .map((file) => ({
        file,
        mtime: statSync(file).mtimeMs,
      }))
      .sort((a, b) => b.mtime - a.mtime);

    for (let i = maxFiles; i < sortedFiles.length; i++) {
      try {
        unlinkSync(sortedFiles[i]!.file);
      } catch {

      }
    }
  } catch {

  }
}

function createLogWriter(filePath?: string): (line: string) => void {
  if (!filePath) {
    return () => {};
  }

  try {
    const stream = createWriteStream(filePath, { flags: "a" });
    stream.on("error", () => {});
    return (line: string) => {
      const timestamp = new Date().toISOString();
      const formatted = `[${timestamp}] ${line}`;
      stream.write(`${formatted}\n`);
    };
  } catch {
    return () => {};
  }
}

/**
 * Initialize or reinitialize debug state with the given config.
 * Call this once at plugin startup after loading config.
 */
export function initializeDebug(config: AntigravityConfig): void {

  const envDebugFlag = env.ANTIGRAVITY_DEBUG ?? "";
  const { debugEnabled } = deriveDebugPolicy({
    configDebug: config.debug,
    configDebugTui: config.debug_tui,
    envDebugFlag,
    envDebugTuiFlag: env.ANTIGRAVITY_DEBUG_TUI,
  });
  const debugTuiEnabled = config.debug_tui || isTruthyFlag(env.ANTIGRAVITY_DEBUG_TUI);
  const logFilePath = debugEnabled ? createLogFilePath(config.log_dir) : undefined;
  const logWriter = createLogWriter(logFilePath);

  if (debugEnabled) {
    ensureGitignoreSync(getConfigDir());
  }

  debugState = {
    debugEnabled,
    debugTuiEnabled,
    logFilePath,
    logWriter,
  };
}

/**
 * Get the current debug state, initializing with defaults if needed.
 * This allows the module to work even before initializeDebug is called.
 */
function getDebugState(): DebugState {
  if (!debugState) {

    const { debugEnabled } = deriveDebugPolicy({
      configDebug: false,
      configDebugTui: false,
      envDebugFlag: env.ANTIGRAVITY_DEBUG,
      envDebugTuiFlag: env.ANTIGRAVITY_DEBUG_TUI,
    });
    const debugTuiEnabled = isTruthyFlag(env.ANTIGRAVITY_DEBUG_TUI);
    const logFilePath = debugEnabled ? createLogFilePath() : undefined;
    const logWriter = createLogWriter(logFilePath);

    debugState = {
      debugEnabled,
      debugTuiEnabled,
      logFilePath,
      logWriter,
    };
  }
  return debugState;
}

export function isDebugEnabled(): boolean {
  return getDebugState().debugEnabled;
}

export function isDebugTuiEnabled(): boolean {
  return getDebugState().debugTuiEnabled;
}

