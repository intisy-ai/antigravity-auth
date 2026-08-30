import crypto from "node:crypto";
import * as antigravity from "../generated/antigravity-orchestrator.teavm.js";

/**
 * The seam onto this provider's transpiled Java, which owns every decision it makes about a
 * request, a model, a project id or an account's quota.
 *
 * @remarks
 * Statically imported, so every caller reaches it synchronously. The bundle is inlined into each
 * deployed entry anyway, so deferring it saves no bytes, and evaluating one costs about 24.7 ms per
 * 842 KB (measured 2026-08-30). That is not worth a promise on the account view's status, quota and
 * availability callbacks, which the host calls synchronously.
 *
 * Values cross as JSON text, which is the shape an account, a request body and a model catalog
 * already have. This module imports nothing else of this provider's, so anything may reach it
 * without a cycle back through the driver entry.
 */
export const orchestrator = antigravity;

/** Real entropy, so the Java never bakes a random value into the bundle. */
export const jsRandom = (): number => Math.random();

/** Real id minting, for the same reason. */
export const jsUuid = (): string => crypto.randomUUID();

/**
 * A synthetic, unpersisted project id, for an account with no discovered managed project.
 *
 * @remarks
 * Used by the login verify-ping and the account-view diagnostic, both of which need one before any
 * account state exists to read it from.
 *
 * @returns the project id
 */
export function generateSyntheticProjectIdViaJava(): string {
  return orchestrator.generateSyntheticProjectIdProd(jsRandom, jsUuid);
}
