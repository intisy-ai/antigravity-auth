import type { ProviderModel } from "../types";

export type ModelThinkingLevel = "minimal" | "low" | "medium" | "high";

export interface ModelThinkingConfig {
  thinkingBudget: number;
}

export interface ModelVariant {
  thinkingLevel?: ModelThinkingLevel;
  thinkingConfig?: ModelThinkingConfig;
  /** Concrete catalog id this variant serves as (effort-variant families). */
  model?: string;
}

export interface ModelLimit {
  context: number;
  output: number;
}

export type ModelModality = "text" | "image" | "pdf";

export interface ModelModalities {
  input: ModelModality[];
  output: ModelModality[];
}

export interface ModelDefinition extends ProviderModel {
  name: string;
  limit: ModelLimit;
  modalities: ModelModalities;
  variants?: Record<string, ModelVariant>;
  // optional provider-defined grouping label, shown verbatim by the loader's
  // Providers tab for models that aren't part of Auto (e.g. a separate quota pool)
  group?: string;
}

export type ModelDefinitions = Record<string, ModelDefinition>;

// The catalog is now fetched live per account (plugin/models-fetch.ts) and cached
// by core-auth. Apps show no antigravity models until the first login;
// this empty default is the pre-login / fetch-failure fallback.
export const MODEL_DEFINITIONS: ModelDefinitions = {};
