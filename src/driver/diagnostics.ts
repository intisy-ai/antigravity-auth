import type { HandlerCtx } from "@intisy-ai/basekit/ir";

/** The two shapes a context's log has, depending on which contract the context comes from. */
type ContextLog = HandlerCtx["log"] | ((message: string) => void);

/**
 * A one-argument diagnostic sink over whichever context a caller holds.
 *
 * @remarks
 * This provider is reached through two different context contracts. A served request carries a
 * `HandlerCtx`, whose `log` is a `Logger` with `debug`/`info`/`warn`/`error`. A model fetch or a
 * login carries a `ProviderCtx`, whose `log` is a plain `(message: string) => void`. Both are
 * correct for their own contract, so the call sites that take either go through this rather than
 * each testing the shape themselves.
 *
 * @param ctx - the context, which a caller may not have
 * @returns a function that writes one diagnostic line, and does nothing when there is nowhere to
 */
export function diagnostic(ctx?: { log?: ContextLog }): (message: string) => void {
  const log = ctx?.log;
  if (typeof log === "function") return log;
  if (log && typeof log.warn === "function") return (message: string) => { log.warn(message); };
  return () => {};
}
