// Live model discovery: ask cloudcode-pa which models the account can actually
// use (v1internal:fetchAvailableModels) and build the OpenCode/Claude catalog
// from the response instead of a hardcoded list. The agent-model set + ranking
// + default all come straight from the API.

import { ANTIGRAVITY_ENDPOINT_FALLBACKS, getAntigravityHeaders } from "../constants";
import type { ModelThinkingLevel, OpencodeModelDefinition, OpencodeModelDefinitions } from "./config/models";

const MODEL_ID_PREFIX = "antigravity-";

interface FetchedModelInfo {
  displayName?: string;
  maxTokens?: number;
  maxOutputTokens?: number;
  supportsImages?: boolean;
  supportsThinking?: boolean;
}

interface FetchAvailableModelsPayload {
  models?: Record<string, FetchedModelInfo>;
  defaultAgentModelId?: string;
  agentModelSorts?: Array<{ groups?: Array<{ modelIds?: string[] }> }>;
  deprecatedModelIds?: Record<string, unknown>;
  imageGenerationModelIds?: string[];
}

export interface AntigravityCatalog {
  models: OpencodeModelDefinitions;
  /** Agent models in recommended order, as FULL catalog ids — Auto ranking source. */
  ranking: string[];
  /** The default agent model, as a full catalog id. */
  defaultModelId?: string;
}

/**
 * Calls v1internal:fetchAvailableModels for the account's project. Retries
 * directly if the (per-account) proxy is unreachable, mirroring the request
 * handle, so a dead proxy never silently empties the catalog.
 */
export async function fetchAvailableModels(
  accessToken: string,
  projectId: string,
  proxy: string | undefined,
  log: (message: string) => void,
): Promise<FetchAvailableModelsPayload | null> {
  const baseInit = {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}`, ...getAntigravityHeaders() },
    body: JSON.stringify(projectId ? { project: projectId } : {}),
  };

  for (const baseEndpoint of ANTIGRAVITY_ENDPOINT_FALLBACKS) {
    const url = `${baseEndpoint}/v1internal:fetchAvailableModels`;
    try {
      let response: Response;
      try {
        response = await fetch(url, { ...baseInit, proxy } as RequestInit & { proxy?: string });
      } catch (proxyError) {
        if (!proxy) throw proxyError;
        log("fetchAvailableModels via proxy failed, retrying directly: " + String(proxyError));
        response = await fetch(url, baseInit as RequestInit);
      }
      if (!response.ok) continue;
      return (await response.json()) as FetchAvailableModelsPayload;
    } catch (error) {
      log("fetchAvailableModels failed at " + baseEndpoint + ": " + String(error));
      continue;
    }
  }
  return null;
}

/** Flattens agentModelSorts into a single ranked id list (the API's recommended order). */
function rankedAgentModelIds(payload: FetchAvailableModelsPayload): string[] {
  const ids: string[] = [];
  for (const sort of payload.agentModelSorts || []) {
    for (const group of sort.groups || []) {
      for (const id of group.modelIds || []) {
        if (!ids.includes(id)) ids.push(id);
      }
    }
  }
  return ids;
}

// The separate Gemini CLI free quota pool (bare ids -> gemini-cli lane/headers).
// These are stable public Gemini models and aren't in the antigravity agent
// ranking, so they're listed as their own labeled group.
const GEMINI_CLI_MODELS: Array<{ id: string; name: string; context: number; output: number }> = [
  { id: "gemini-2.5-flash", name: "Gemini 2.5 Flash", context: 1048576, output: 65536 },
  { id: "gemini-2.5-pro", name: "Gemini 2.5 Pro", context: 1048576, output: 65536 },
  { id: "gemini-3-flash-preview", name: "Gemini 3 Flash Preview", context: 1048576, output: 65536 },
  { id: "gemini-3-pro-preview", name: "Gemini 3 Pro Preview", context: 1048576, output: 65535 },
  { id: "gemini-3.1-pro-preview", name: "Gemini 3.1 Pro Preview", context: 1048576, output: 65535 },
];

function buildModelEntry(rawId: string, info: FetchedModelInfo): OpencodeModelDefinition {
  return {
    name: (info.displayName || rawId) + " (Antigravity)",
    limit: { context: info.maxTokens || 200000, output: info.maxOutputTokens || 65535 },
    modalities: {
      input: info.supportsImages ? ["text", "image", "pdf"] : ["text", "pdf"],
      output: ["text"],
    },
  };
}

// Effort-variant grouping: cloudcode-pa exposes one backend model PER effort level
// (e.g. gemini-3.5-flash-extra-low / gemini-3.5-flash-low / gemini-3-flash-agent all
// serve "Gemini 3.5 Flash" at Low / Medium / High). The raw keys are opaque routing
// ids whose suffixes do NOT match the effort in the displayName — listing them
// separately read as label↔id mismatches. Each family collapses into ONE catalog
// entry whose `variants` map an effort level to the concrete backend id; the driver
// swaps in the variant id at request time from the requested thinking level.
const EFFORT_TAG = /\s*\((minimal|extra\s?low|low|medium|high)\)\s*$/i;

function effortTagOf(displayName: string): { base: string; level?: string } {
  const m = displayName.match(EFFORT_TAG);
  if (!m) return { base: displayName };
  const tag = m[1]!.toLowerCase().replace(/\s+/g, "-");
  return { base: displayName.replace(EFFORT_TAG, "").trim(), level: tag === "extra-low" ? "minimal" : tag };
}

/**
 * Builds the catalog from a fetchAvailableModels payload: the recommended agent
 * models (in order), minus deprecated/image-generation ids. Returns the prefixed
 * OpenCode catalog plus the raw ranking + default for Auto.
 */
export function buildAntigravityCatalog(payload: FetchAvailableModelsPayload): AntigravityCatalog {
  const models = payload.models || {};
  const deprecated = new Set(Object.keys(payload.deprecatedModelIds || {}));
  const imageOnly = new Set(payload.imageGenerationModelIds || []);

  const ranked = rankedAgentModelIds(payload).filter(
    (id) => models[id] && !deprecated.has(id) && !imageOnly.has(id),
  );

  const catalog: OpencodeModelDefinitions = {};
  // "Auto" first (routes to the flagship via the resolver alias; becomes a
  // configurable core feature in a later phase). Kept so it never regresses.
  catalog[MODEL_ID_PREFIX + "auto"] = {
    name: "Auto",
    limit: { context: 1048576, output: 65535 },
    modalities: { input: ["text", "image", "pdf"], output: ["text"] },
    variants: {
      minimal: { thinkingLevel: "minimal" },
      low: { thinkingLevel: "low" },
      medium: { thinkingLevel: "medium" },
      high: { thinkingLevel: "high" },
    },
  };
  // Pass 1 — group ranked models into effort families by displayName base.
  interface EffortGroup { canonical: string; base: string; members: Array<{ rawId: string; level: string }> }
  const groups = new Map<string, EffortGroup>();
  const emitOrder: string[] = [];   // canonical raw id per emitted entry, ranked order
  for (const rawId of ranked) {
    const { base, level } = effortTagOf(models[rawId]!.displayName || rawId);
    if (!level) { emitOrder.push(rawId); continue; }
    let group = groups.get(base);
    if (!group) { group = { canonical: rawId, base, members: [] }; groups.set(base, group); emitOrder.push(rawId); }
    group.members.push({ rawId, level });
  }
  // Pass 2 — emit. Single-member families keep their original tagged name; multi-
  // member families collapse into the canonical (API-preferred) id with variants.
  const groupOf = new Map<string, EffortGroup>();
  for (const group of groups.values()) if (group.members.length > 1) groupOf.set(group.canonical, group);
  for (const rawId of emitOrder) {
    const entry = buildModelEntry(rawId, models[rawId]!);
    const group = groupOf.get(rawId);
    if (group) {
      entry.name = group.base + " (Antigravity)";
      entry.variants = Object.fromEntries(group.members.map((member) => [
        member.level,
        { thinkingLevel: member.level as ModelThinkingLevel, model: MODEL_ID_PREFIX + member.rawId },
      ]));
    }
    catalog[MODEL_ID_PREFIX + rawId] = entry;
  }
  // Gemini CLI quota pool (bare ids, distinct lane) — a second free pool. The
  // group label is what the loader's Providers tab shows verbatim (provider-defined).
  for (const cli of GEMINI_CLI_MODELS) {
    catalog[cli.id] = {
      // Suffix the source in the NAME itself: several of these ids also exist in the
      // antigravity catalog (same underlying Gemini model, different pool/lane), so
      // without a suffix the name alone would read as antigravity. "(Gemini CLI)"
      // mirrors the "(Antigravity)" suffix the fetched models carry.
      name: cli.name + " (Gemini CLI)",
      limit: { context: cli.context, output: cli.output },
      modalities: { input: ["text", "image", "pdf"], output: ["text"] },
      group: "Gemini CLI · separate free pool (not in Auto)",
    };
  }

  // The API default may name a hidden family member — map it to its family's
  // canonical (emitted) id.
  let defaultRaw = payload.defaultAgentModelId && ranked.includes(payload.defaultAgentModelId)
    ? payload.defaultAgentModelId
    : emitOrder[0];
  if (defaultRaw && !catalog[MODEL_ID_PREFIX + defaultRaw]) {
    for (const group of groups.values()) {
      if (group.members.some((member) => member.rawId === defaultRaw)) { defaultRaw = group.canonical; break; }
    }
  }

  // ranking/default use the FULL catalog ids (same keys as `models` + the request
  // model id) so consumers (loader tab, Auto router) never need a provider prefix.
  return {
    models: catalog,
    ranking: emitOrder.map((id) => MODEL_ID_PREFIX + id),
    defaultModelId: defaultRaw ? MODEL_ID_PREFIX + defaultRaw : undefined,
  };
}
