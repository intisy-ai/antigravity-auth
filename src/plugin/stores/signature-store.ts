/** One thinking block and the signature the upstream returned it with. */
export interface SignedThinking {
  /** The thinking text the signature covers. */
  text: string;
  /** The signature, which the upstream requires back on the next turn. */
  signature: string;
}

/** Where signed thinking blocks are held for the life of the process. */
export interface SignatureStore {
  /**
   * The pair held under one key.
   *
   * @param sessionKey - the signature key
   * @returns the pair, or `undefined` when nothing is held
   */
  get(sessionKey: string): SignedThinking | undefined;
  /**
   * Records a pair.
   *
   * @param sessionKey - the key to hold it under
   * @param value - the text and its signature
   */
  set(sessionKey: string, value: SignedThinking): void;
  /**
   * Whether a key holds a pair.
   *
   * @param sessionKey - the signature key
   * @returns true when one is held
   */
  has(sessionKey: string): boolean;
  /**
   * Drops whatever one key holds.
   *
   * @param sessionKey - the signature key
   */
  delete(sessionKey: string): void;
}

/** The thinking text accumulated per candidate while one response streams. */
export interface ThoughtBuffer {
  /**
   * What has accumulated for one candidate.
   *
   * @param index - which candidate
   * @returns its text so far, or `undefined` when there is none
   */
  get(index: number): string | undefined;
  /**
   * Records what has accumulated for one candidate.
   *
   * @param index - which candidate
   * @param text - the text so far
   */
  set(index: number, text: string): void;
  /** Drops everything accumulated, at the end of a stream. */
  clear(): void;
}

/**
 * A fresh in-memory signature store.
 *
 * @returns the store
 */
export function createSignatureStore(): SignatureStore {
  const store = new Map<string, SignedThinking>();

  return {
    get: (key: string) => store.get(key),
    set: (key: string, value: SignedThinking) => {
      store.set(key, value);
    },
    has: (key: string) => store.has(key),
    delete: (key: string) => {
      store.delete(key);
    },
  };
}

/**
 * A fresh buffer for one stream's accumulated thinking text.
 *
 * @returns the buffer
 */
export function createThoughtBuffer(): ThoughtBuffer {
  const buffer = new Map<number, string>();

  return {
    get: (index: number) => buffer.get(index),
    set: (index: number, text: string) => {
      buffer.set(index, text);
    },
    clear: () => buffer.clear(),
  };
}

/** The one store every request in this process shares, so a signature survives between turns. */
export const defaultSignatureStore = createSignatureStore();
