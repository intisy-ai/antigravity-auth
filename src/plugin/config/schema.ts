/**
 * Configuration types + defaults for the antigravity provider.
 *
 * Config now lives entirely in core's per-plugin store (config/antigravity.json),
 * read/written via core's loadConfig/getConfigValue/setConfigValue. This file only
 * declares the shape (AntigravityConfig) and the DEFAULT_CONFIG registered with
 * defineConfig("antigravity", ...), no validation, no file access.
 *
 * Only keys with a real runtime consumer are declared here: the 9 pre-existing
 * functional keys plus the 3 wired-up features (request_jitter_max_ms, cli_first,
 * signature_cache). The rest of the historical schema (quiet_mode, toast_scope,
 * debug_gemini_payloads, empty_response_*, tool_id_recovery, proactive_*,
 * max_rate_limit_wait_seconds, quota_fallback, model_ranking, fallback_enabled,
 * auto_mode*, pid_offset_enabled, switch_on_first_rate_limit, scheduling_mode,
 * max_cache_first_wait_seconds, failure_ttl_seconds, soft_quota_*,
 * quota_refresh_interval_minutes, health_score, token_bucket) had no consumer in
 * either the TS driver or the JVM provider, deleted, not just hidden.
 */

/**
 * Account selection strategy for distributing requests across accounts.
 * - `sticky`: same account until rate-limited (preserves prompt cache).
 * - `round-robin`: rotate every request (maximum throughput).
 * - `hybrid` (default): health score + token bucket + LRU freshness.
 */
export type AccountSelectionStrategy = "sticky" | "round-robin" | "hybrid";

/** Signature cache configuration for persisting thinking block signatures to disk. */
export interface SignatureCacheConfig {
  /** Enable disk caching of signatures. */
  enabled: boolean;
  /** In-memory TTL in seconds. */
  memory_ttl_seconds: number;
  /** Disk TTL in seconds. */
  disk_ttl_seconds: number;
  /** Background write interval in seconds. */
  write_interval_seconds: number;
}

/**
 * Main configuration for the Antigravity OAuth provider.
 *
 * @remarks
 * A type alias rather than an interface, because basekit's shared account-manager and
 * retry-backoff helpers take a `Record<string, unknown>` and TypeScript gives only an alias the
 * implicit index signature that makes one assignable.
 */
export type AntigravityConfig = {
  /** JSON Schema reference for IDE support. */
  $schema?: string;

  /** Enable debug logging to file. */
  debug: boolean;
  /** Show debug logs in the TUI log panel (independent of file logging). */
  debug_tui: boolean;
  /** Custom directory for debug logs. */
  log_dir?: string;

  /** Preserve Claude thinking blocks via signature caching instead of stripping. */
  keep_thinking: boolean;
  /** Signature cache config (only used when keep_thinking is enabled). */
  signature_cache?: SignatureCacheConfig;

  /** Inject parameter signatures + strict tool rules to curb Claude tool hallucination. */
  claude_tool_hardening: boolean;
  /** Add top-level cache_control to Claude prompts when absent. */
  claude_prompt_auto_caching: boolean;

  /** Prefer gemini-cli routing before Antigravity for Gemini models. */
  cli_first: boolean;

  /** Strategy for selecting accounts when making requests. */
  account_selection_strategy: AccountSelectionStrategy;

  /** Default retry delay (s) when the API returns no retry-after header. */
  default_retry_after_seconds: number;
  /** Max backoff delay (s) for exponential retry. */
  max_backoff_seconds: number;
  /** Max random delay (ms) before each API request (0 = disabled). */
  request_jitter_max_ms: number;
};

/**
 * Default configuration values.
 */
export const DEFAULT_CONFIG: AntigravityConfig = {
  debug: false,
  debug_tui: false,
  keep_thinking: false,
  claude_tool_hardening: true,
  claude_prompt_auto_caching: false,
  cli_first: false,
  account_selection_strategy: 'hybrid',
  default_retry_after_seconds: 60,
  max_backoff_seconds: 60,
  request_jitter_max_ms: 0,
  signature_cache: {
    enabled: true,
    memory_ttl_seconds: 3600,
    disk_ttl_seconds: 172800,
    write_interval_seconds: 60,
  },
};
