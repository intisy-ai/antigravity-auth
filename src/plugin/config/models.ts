import type { ProviderModel } from "../types.js";

/** How hard a model is asked to think, for the models that take a level rather than a budget. */
export type ModelThinkingLevel = "minimal" | "low" | "medium" | "high";

/** How hard a model is asked to think, for the models that take a numeric budget. */
export interface ModelThinkingConfig {
  /** The budget, in tokens. */
  thinkingBudget: number;
}

/** One effort variant of a model, which the catalog offers as its own selectable entry. */
export interface ModelVariant {
  /** The level this variant asks for. */
  thinkingLevel?: ModelThinkingLevel;
  /** The budget this variant asks for, for a model that takes one. */
  thinkingConfig?: ModelThinkingConfig;
  /** Concrete catalog id this variant serves as (effort-variant families). */
  model?: string;
}

/** How much text a model accepts and produces. */
export interface ModelLimit {
  /** Its input token limit. */
  context: number;
  /** Its output token limit. */
  output: number;
}

/** One kind of content a model can read or write. */
export type ModelModality = "text" | "image" | "pdf";

/** What a model can read and what it can write. */
export interface ModelModalities {
  /** What it accepts. */
  input: ModelModality[];
  /** What it produces. */
  output: ModelModality[];
}

/** One model as this provider's catalog describes it. */
export interface ModelDefinition extends ProviderModel {
  /** What a surface shows for it. */
  name: string;
  /** How much text it accepts and produces. */
  limit: ModelLimit;
  /** What it can read and write. */
  modalities: ModelModalities;
  /** Its effort variants, when it has any. */
  variants?: Record<string, ModelVariant>;
  /**
   * A grouping label a surface shows verbatim.
   *
   * @remarks
   * Set for a model that is not part of automatic selection, such as one on a separate quota pool.
   */
  group?: string;
}

/** A whole catalog, keyed by model id. */
export type ModelDefinitions = Record<string, ModelDefinition>;

/**
 * The catalog shown before any account exists.
 *
 * @remarks
 * Empty on purpose: the real catalog is fetched live per account and cached by the shared account
 * library, so an app shows no models of this provider until the first login, and this is what it
 * falls back to before that and when a fetch fails.
 */
export const MODEL_DEFINITIONS: ModelDefinitions = {};
