import { createWriteStream, mkdirSync, readdirSync, statSync, unlinkSync } from "node:fs";
import { join } from "node:path";
import { env } from "node:process";
import { homedir } from "node:os";
import type { AntigravityConfig } from "./config";
import {
  deriveDebugPolicy,
  formatAccountContextLabel,
  formatAccountLabel,
  formatBodyPreviewForLog,
  formatErrorForLog,
  isTruthyFlag,
  truncateTextForLog,
} from "./logging-utils";
import { ensureGitignoreSync } from "./storage";

const MAX_BODY_PREVIEW_CHARS = 12000;
const MAX_BODY_LOG_CHARS = 50000;

export const DEBUG_MESSAGE_PREFIX = "[opencode-antigravity-auth debug]";

// =============================================================================

// =============================================================================

interface DebugState {
  debugEnabled: boolean;
  debugTuiEnabled: boolean;
  logFilePath: string | undefined;
  logWriter: (line: string) => void;
}

let debugState: DebugState | null = null;

/**
 * Get the OS-specific config directory.
 */
function getConfigDir(): string {
  const platform = process.platform;
  if (platform === "win32") {
    return join(env.APPDATA || join(homedir(), "AppData", "Roaming"), "opencode");
  }
  const xdgConfig = env.XDG_CONFIG_HOME || join(homedir(), ".config");
  return join(xdgConfig, "opencode");
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

/**
 * Builds a timestamped log file path.
 */
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

/**
 * Creates a log writer function that writes to a file.
 */
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

  const envDebugFlag = env.OPENCODE_ANTIGRAVITY_DEBUG ?? "";
  const { debugEnabled } = deriveDebugPolicy({
    configDebug: config.debug,
    configDebugTui: config.debug_tui,
    envDebugFlag,
    envDebugTuiFlag: env.OPENCODE_ANTIGRAVITY_DEBUG_TUI,
  });
  const debugTuiEnabled = config.debug_tui || isTruthyFlag(env.OPENCODE_ANTIGRAVITY_DEBUG_TUI);
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
      envDebugFlag: env.OPENCODE_ANTIGRAVITY_DEBUG,
      envDebugTuiFlag: env.OPENCODE_ANTIGRAVITY_DEBUG_TUI,
    });
    const debugTuiEnabled = isTruthyFlag(env.OPENCODE_ANTIGRAVITY_DEBUG_TUI);
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

// =============================================================================

// =============================================================================

export function isDebugEnabled(): boolean {
  return getDebugState().debugEnabled;
}

export function isDebugTuiEnabled(): boolean {
  return getDebugState().debugTuiEnabled;
}

export interface AntigravityDebugContext {
  id: string;
  streaming: boolean;
  startedAt: number;
}

interface AntigravityDebugRequestMeta {
  originalUrl: string;
  resolvedUrl: string;
  method?: string;
  headers?: HeadersInit;
  body?: BodyInit | null;
  streaming: boolean;
  projectId?: string;
}

interface AntigravityDebugResponseMeta {
  body?: string;
  note?: string;
  error?: unknown;
  headersOverride?: HeadersInit;
}

let requestCounter = 0;

/**
 * Logs response details for a previously started debug trace.
 */
export function logAntigravityDebugResponse(
  context: AntigravityDebugContext | null | undefined,
  response: Response,
  meta: AntigravityDebugResponseMeta = {},
): void {
  const state = getDebugState();
  if (!state.debugEnabled || !context) {
    return;
  }

  const durationMs = Date.now() - context.startedAt;
  logDebug(
    `[Antigravity Debug ${context.id}] Response ${response.status} ${response.statusText} (${durationMs}ms)`,
  );
  logDebug(
    `[Antigravity Debug ${context.id}] Response Headers: ${JSON.stringify(
      maskHeaders(meta.headersOverride ?? response.headers),
    )}`,
  );

  if (meta.note) {
    logDebug(`[Antigravity Debug ${context.id}] Note: ${meta.note}`);
  }

  if (meta.error) {
    logDebug(`[Antigravity Debug ${context.id}] Error: ${formatErrorForLog(meta.error)}`);
  }

  if (meta.body) {
    logDebug(
      `[Antigravity Debug ${context.id}] Response Body Preview: ${truncateTextForLog(meta.body, MAX_BODY_PREVIEW_CHARS)}`,
    );
  }
}

/**
 * Obscures sensitive headers and returns a plain object for logging.
 */
function maskHeaders(headers?: HeadersInit | Headers): Record<string, string> {
  if (!headers) {
    return {};
  }

  const result: Record<string, string> = {};
  const parsed = headers instanceof Headers ? headers : new Headers(headers);
  parsed.forEach((value, key) => {
    if (key.toLowerCase() === "authorization") {
      result[key] = "[redacted]";
    } else {
      result[key] = value;
    }
  });
  return result;
}

/**
 * Writes a single debug line using the configured writer.
 */
function logDebug(line: string): void {
  getDebugState().logWriter(line);
}

function runWithDebugEnabled(action: () => void): void {
  if (!getDebugState().debugEnabled) return;
  action();
}

/**
 * Logs cache hit/miss information from response usage metadata.
 */
export function logCacheStats(
  model: string,
  cacheReadTokens: number,
  cacheWriteTokens: number,
  totalInputTokens: number,
): void {
  runWithDebugEnabled(() => {
    const cacheHitRate = totalInputTokens > 0 
      ? Math.round((cacheReadTokens / totalInputTokens) * 100) 
      : 0;
    const status = cacheReadTokens > 0 ? "HIT" : (cacheWriteTokens > 0 ? "WRITE" : "MISS");
    logDebug(`[Cache] ${status} model=${model} read=${cacheReadTokens} write=${cacheWriteTokens} total=${totalInputTokens} hitRate=${cacheHitRate}%`);
  });
}

