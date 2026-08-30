/**
 * Device Fingerprint Generator for Rate Limit Mitigation
 *
 * Ported from antigravity-claude-proxy PR #170
 * https://github.com/badrisnarayanan/antigravity-claude-proxy/pull/170
 *
 * Generates randomized device fingerprints to help distribute API usage
 * across different apparent device identities.
 */

import * as crypto from "node:crypto";
import * as os from "node:os";
import { getVersionList } from "./versions.js";
import { orchestrator } from "../driver/java.js";

const OS_VERSIONS: Record<string, string[]> = {
  darwin: ["10.15.7", "11.6.8", "12.6.3", "13.5.2", "14.2.1", "14.5"],
  win32: ["10.0.19041", "10.0.19042", "10.0.19043", "10.0.22000", "10.0.22621", "10.0.22631"],
  linux: ["5.15.0", "5.19.0", "6.1.0", "6.2.0", "6.5.0", "6.6.0"],
};

const ARCHITECTURES = ["x64", "arm64"];

const IDE_TYPES = [
  "ANTIGRAVITY",
] as const;

const PLATFORMS = [
  "WINDOWS",
  "MACOS",
] as const;

const SDK_CLIENTS = [
  "google-cloud-sdk vscode_cloudshelleditor/0.1",
  "google-cloud-sdk vscode/1.86.0",
  "google-cloud-sdk vscode/1.87.0",
  "google-cloud-sdk vscode/1.96.0",
];

/** The IDE identity a request claims, which the upstream validates. */
export interface ClientMetadata {
  /** Which IDE it claims to be. */
  ideType: string;
  /** Which platform it claims to run on. */
  platform: string;
  /** Which plugin it claims to be. */
  pluginType: string;
}

/**
 * One account's stable device identity.
 *
 * @remarks
 * Minted once per account and persisted, so an account presents the same machine every time rather
 * than a fresh one per request.
 */
export interface Fingerprint {
  /** What identifies the machine. */
  deviceId: string;
  /** What identifies this install's session. */
  sessionToken: string;
  /** What the account's requests claim to be. */
  userAgent: string;
  /** Which client library they claim to use. */
  apiClient: string;
  /** The IDE identity they claim. */
  clientMetadata: ClientMetadata;
  /** When the fingerprint was minted, in epoch milliseconds. */
  createdAt: number;
  /** The Antigravity version embedded in userAgent; drifts forward over time. */
  version?: string;
  /** When `version` was last (re)picked (informational). */
  versionPickedAt?: number;
  /** Per-account randomized epoch ms for the next forward drift, staggers updates
   *  so accounts never migrate in lockstep. */
  nextVersionDriftAt?: number;
  /** @deprecated Kept for backward compat with stored fingerprints */
  quotaUser?: string;
}

const PLATFORM_CHOICES = ["darwin", "win32"] as const;
type PlatformChoice = typeof PLATFORM_CHOICES[number];

function randomFrom<T>(arr: readonly T[]): T {
  return arr[Math.floor(Math.random() * arr.length)]!;
}

function platformToDisplayName(platform: string): "WINDOWS" | "MACOS" {
  return platform === "win32" ? "WINDOWS" : "MACOS";
}

function generateDeviceId(): string {
  return crypto.randomUUID();
}

function generateSessionToken(): string {
  return crypto.randomBytes(16).toString("hex");
}

/**
 * Generate a randomized device fingerprint.
 * Each fingerprint represents a unique "device" identity.
 */
export async function generateFingerprint(): Promise<Fingerprint> {
  const platform = randomFrom(PLATFORM_CHOICES);
  const arch = randomFrom(ARCHITECTURES);
  const osVersion = randomFrom(OS_VERSIONS[platform] ?? OS_VERSIONS.darwin!);
  // weighted toward newer real versions, AntigravityVersions.pickVersion (Java), real jsRandom.
  const version = orchestrator.pickVersionProd(JSON.stringify(getVersionList()), "", () => Math.random());

  return {
    deviceId: generateDeviceId(),
    sessionToken: generateSessionToken(),
    userAgent: `antigravity/${version} ${platform}/${arch}`,
    apiClient: randomFrom(SDK_CLIENTS),
    clientMetadata: {
      ideType: randomFrom(IDE_TYPES),
      platform: platformToDisplayName(platform),
      pluginType: "GEMINI",
    },
    createdAt: Date.now(),
    version,
    versionPickedAt: Date.now(),
  };
}


