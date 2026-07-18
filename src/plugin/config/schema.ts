/**
 * Configuration types + defaults for the antigravity provider.
 *
 * Config now lives entirely in core's per-plugin store (config/antigravity.json),
 * read/written via core's loadConfig/getConfigValue/setConfigValue. This file only
 * declares the shape (AntigravityConfig) and the DEFAULT_CONFIG registered with
 * defineConfig("antigravity", ...) — no validation, no file access.
 */

/**
 * Account selection strategy for distributing requests across accounts.
 * - `sticky`: same account until rate-limited (preserves prompt cache).
 * - `round-robin`: rotate every request (maximum throughput).
 * - `hybrid` (default): health score + token bucket + LRU freshness.
 */
export type AccountSelectionStrategy = "sticky" | "round-robin" | "hybrid";

/** Toast notification scope — root sessions only (default) or all sessions. */
export type ToastScope = "root_only" | "all";

/** Scheduling mode for rate-limit behavior. */
export type SchedulingMode = "cache_first" | "balance" | "performance_first";

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

/** Main configuration for the Antigravity OAuth provider. */
export interface AntigravityConfig {
  /** JSON Schema reference for IDE support. */
  $schema?: string;

  /** Suppress most toast notifications (recovery toasts always show). */
  quiet_mode: boolean;
  /** Which sessions show toasts: root only (default) or all. */
  toast_scope: ToastScope;
  /** Enable debug logging to file. */
  debug: boolean;
  /** Show debug logs in the TUI log panel (independent of file logging). */
  debug_tui: boolean;
  /** Write the raw payload sent to Gemini models to a debug log file. */
  debug_gemini_payloads: boolean;
  /** Custom directory for debug logs. */
  log_dir?: string;

  /** Preserve Claude thinking blocks via signature caching instead of stripping. */
  keep_thinking: boolean;
  /** Signature cache config (only used when keep_thinking is enabled). */
  signature_cache?: SignatureCacheConfig;

  /** Max retry attempts when Antigravity returns an empty response. */
  empty_response_max_attempts: number;
  /** Delay in ms between empty-response retries. */
  empty_response_retry_delay_ms: number;

  /** Enable tool ID orphan recovery for mismatched tool response IDs. */
  tool_id_recovery: boolean;

  /** Inject parameter signatures + strict tool rules to curb Claude tool hallucination. */
  claude_tool_hardening: boolean;
  /** Add top-level cache_control to Claude prompts when absent. */
  claude_prompt_auto_caching: boolean;

  /** Refresh tokens in the background before they expire. */
  proactive_token_refresh: boolean;
  /** Seconds before token expiry to trigger proactive refresh. */
  proactive_refresh_buffer_seconds: number;
  /** Interval between proactive refresh checks in seconds. */
  proactive_refresh_check_interval_seconds: number;

  /** Max seconds to wait when all accounts are rate-limited (0 = wait indefinitely). */
  max_rate_limit_wait_seconds: number;

  /** @deprecated Ignored at runtime; kept for backward compatibility. */
  quota_fallback: boolean;
  /** Prefer gemini-cli routing before Antigravity for Gemini models. */
  cli_first: boolean;

  /** Ordered list of models from best to worst (defines fallback stages). */
  model_ranking?: string[];
  /** Whether model fallback is enabled at all. */
  fallback_enabled: boolean;
  /** Enable "auto" model mode (antigravity-auto picks the best available model). */
  auto_mode: boolean;
  /** Auto mode: which stage to target. */
  auto_mode_stage?: "best" | "high" | "balanced" | "fastest";

  /** Strategy for selecting accounts when making requests. */
  account_selection_strategy: AccountSelectionStrategy;
  /** PID-based account offset for multi-session load distribution. */
  pid_offset_enabled: boolean;
  /** Switch account immediately on first rate limit (after 1s delay). */
  switch_on_first_rate_limit: boolean;
  /** Scheduling mode for rate-limit behavior. */
  scheduling_mode: SchedulingMode;
  /** Max seconds to wait for the same account in cache_first mode. */
  max_cache_first_wait_seconds: number;
  /** TTL in seconds for failure-count expiration. */
  failure_ttl_seconds: number;

  /** Default retry delay (s) when the API returns no retry-after header. */
  default_retry_after_seconds: number;
  /** Max backoff delay (s) for exponential retry. */
  max_backoff_seconds: number;
  /** Max random delay (ms) before each API request (0 = disabled). */
  request_jitter_max_ms: number;

  /** Soft quota threshold percent (1-100); skip account at/above this usage. */
  soft_quota_threshold_percent: number;
  /** How often to refresh quota data in the background (minutes; 0 = manual only). */
  quota_refresh_interval_minutes: number;
  /** How long the quota cache is fresh for threshold checks ("auto" or minutes). */
  soft_quota_cache_ttl_minutes: "auto" | number;

  /** Health-score tuning for account selection. */
  health_score?: {
    initial: number;
    success_reward: number;
    rate_limit_penalty: number;
    failure_penalty: number;
    recovery_rate_per_hour: number;
    min_usable: number;
    max_score: number;
  };

  /** Token-bucket tuning for account selection. */
  token_bucket?: {
    max_tokens: number;
    regeneration_rate_per_minute: number;
    initial_tokens: number;
  };
}

/**
 * Default configuration values.
 */
export const DEFAULT_CONFIG: AntigravityConfig = {
  quiet_mode: false,
  toast_scope: 'root_only',
  debug: false,
  debug_tui: false,
  debug_gemini_payloads: false,
  keep_thinking: false,
  empty_response_max_attempts: 4,
  empty_response_retry_delay_ms: 2000,
  tool_id_recovery: true,
  claude_tool_hardening: true,
  claude_prompt_auto_caching: false,
  proactive_token_refresh: true,
  proactive_refresh_buffer_seconds: 1800,
  proactive_refresh_check_interval_seconds: 300,
  max_rate_limit_wait_seconds: 300,
  quota_fallback: false,
  cli_first: false,
  fallback_enabled: false,
  auto_mode: true,
  account_selection_strategy: 'hybrid',
  pid_offset_enabled: false,
  switch_on_first_rate_limit: true,
  scheduling_mode: 'cache_first',
  max_cache_first_wait_seconds: 60,
  failure_ttl_seconds: 3600,
  default_retry_after_seconds: 60,
  max_backoff_seconds: 60,
  request_jitter_max_ms: 0,
  soft_quota_threshold_percent: 90,
  quota_refresh_interval_minutes: 15,
  soft_quota_cache_ttl_minutes: "auto",
  signature_cache: {
    enabled: true,
    memory_ttl_seconds: 3600,
    disk_ttl_seconds: 172800,
    write_interval_seconds: 60,
  },
  health_score: {
    initial: 70,
    success_reward: 1,
    rate_limit_penalty: -10,
    failure_penalty: -20,
    recovery_rate_per_hour: 2,
    min_usable: 50,
    max_score: 100,
  },
  token_bucket: {
    max_tokens: 50,
    regeneration_rate_per_minute: 6,
    initial_tokens: 50,
  },
};
