// Shared type shapes for the plugin's auth and model-config code.

/** Stored auth for an account signed in with OAuth, which is every account this provider has. */
export interface OAuthAuthDetails {
  /** Discriminates this shape from the others. */
  type: "oauth";
  /** The refresh token, packed with the account's project ids. */
  refresh: string;
  /** The current access token. */
  access?: string;
  /** When that token expires, in epoch milliseconds. */
  expires?: number;
}

/** Stored auth for an account signed in with a key rather than OAuth. */
export interface ApiKeyAuthDetails {
  /** Discriminates this shape from the others. */
  type: "api_key";
  /** The key itself. */
  key: string;
}

/** Stored auth of any other kind, which this provider reads but never mints. */
export interface NonOAuthAuthDetails {
  /** What kind it is. */
  type: string;
  /** Whatever else the kind carries. */
  [key: string]: unknown;
}

/** Stored auth, whichever kind it is. */
export type AuthDetails = OAuthAuthDetails | ApiKeyAuthDetails | NonOAuthAuthDetails;

/** One model as a surface renders it. */
export interface ProviderModel {
  /** What a token costs, when the upstream reports it. */
  cost?: {
    /** Per input token. */
    input: number;
    /** Per output token. */
    output: number;
  };
  /** Whatever else the app attaches to a model. */
  [key: string]: unknown;
}

/** The three segments a stored refresh string packs together. */
export interface RefreshParts {
  /** The OAuth refresh token, which is the durable credential. */
  refreshToken: string;
  /** The project the account discovered. */
  projectId?: string;
  /** The managed project the account was onboarded to. */
  managedProjectId?: string;
}

