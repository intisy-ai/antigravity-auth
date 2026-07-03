/**
 * Storage utilities for reading OpenCode's session data.
 * 
 * Based on oh-my-opencode/src/hooks/session-recovery/storage.ts
 */

import { existsSync, mkdirSync, readdirSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { MESSAGE_STORAGE, PART_STORAGE, THINKING_TYPES, META_TYPES } from "./constants";
import type { StoredMessageMeta, StoredPart, StoredTextPart } from "./types";

// =============================================================================

// =============================================================================

export function getMessageDir(sessionID: string): string {
  if (!existsSync(MESSAGE_STORAGE)) return "";

  const directPath = join(MESSAGE_STORAGE, sessionID);
  if (existsSync(directPath)) {
    return directPath;
  }


  try {
    for (const dir of readdirSync(MESSAGE_STORAGE)) {
      const sessionPath = join(MESSAGE_STORAGE, dir, sessionID);
      if (existsSync(sessionPath)) {
        return sessionPath;
      }
    }
  } catch {

  }

  return "";
}

// =============================================================================

// =============================================================================

export function readMessages(sessionID: string): StoredMessageMeta[] {
  const messageDir = getMessageDir(sessionID);
  if (!messageDir || !existsSync(messageDir)) return [];

  const messages: StoredMessageMeta[] = [];
  try {
    for (const file of readdirSync(messageDir)) {
      if (!file.endsWith(".json")) continue;
      try {
        const content = readFileSync(join(messageDir, file), "utf-8");
        messages.push(JSON.parse(content));
      } catch {
        continue;
      }
    }
  } catch {
    return [];
  }

  return messages.sort((a, b) => {
    const aTime = a.time?.created ?? 0;
    const bTime = b.time?.created ?? 0;
    if (aTime !== bTime) return aTime - bTime;
    return a.id.localeCompare(b.id);
  });
}

// =============================================================================

// =============================================================================

export function readParts(messageID: string): StoredPart[] {
  const partDir = join(PART_STORAGE, messageID);
  if (!existsSync(partDir)) return [];

  const parts: StoredPart[] = [];
  try {
    for (const file of readdirSync(partDir)) {
      if (!file.endsWith(".json")) continue;
      try {
        const content = readFileSync(join(partDir, file), "utf-8");
        parts.push(JSON.parse(content));
      } catch {
        continue;
      }
    }
  } catch {
    return [];
  }

  return parts;
}

// =============================================================================

// =============================================================================

export function hasContent(part: StoredPart): boolean {
  if (THINKING_TYPES.has(part.type)) return false;
  if (META_TYPES.has(part.type)) return false;

  if (part.type === "text") {
    const textPart = part as StoredTextPart;
    return !!(textPart.text?.trim());
  }

  if (part.type === "tool" || part.type === "tool_use") {
    return true;
  }

  if (part.type === "tool_result") {
    return true;
  }

  return false;
}

// =============================================================================

export function findMessagesWithThinkingBlocks(sessionID: string): string[] {
  const messages = readMessages(sessionID);
  const result: string[] = [];

  for (const msg of messages) {
    if (msg.role !== "assistant") continue;

    const parts = readParts(msg.id);
    const hasThinking = parts.some((p) => THINKING_TYPES.has(p.type));
    if (hasThinking) {
      result.push(msg.id);
    }
  }

  return result;
}

export function findMessagesWithOrphanThinking(sessionID: string): string[] {
  const messages = readMessages(sessionID);
  const result: string[] = [];

  for (let i = 0; i < messages.length; i++) {
    const msg = messages[i];
    if (!msg || msg.role !== "assistant") continue;

    const parts = readParts(msg.id);
    if (parts.length === 0) continue;

    const sortedParts = [...parts].sort((a, b) => a.id.localeCompare(b.id));
    const firstPart = sortedParts[0];
    if (!firstPart) continue;

    const firstIsThinking = THINKING_TYPES.has(firstPart.type);


    if (!firstIsThinking) {
      result.push(msg.id);
    }
  }

  return result;
}

export function prependThinkingPart(sessionID: string, messageID: string): boolean {
  const partDir = join(PART_STORAGE, messageID);

  try {
    if (!existsSync(partDir)) {
      mkdirSync(partDir, { recursive: true });
    }

    const partId = "prt_0000000000_thinking";
    const part = {
      id: partId,
      sessionID,
      messageID,
      type: "thinking",
      thinking: "",
      synthetic: true,
    };

    writeFileSync(join(partDir, `${partId}.json`), JSON.stringify(part, null, 2));
    return true;
  } catch {
    return false;
  }
}

export function stripThinkingParts(messageID: string): boolean {
  const partDir = join(PART_STORAGE, messageID);
  if (!existsSync(partDir)) return false;

  let anyRemoved = false;
  try {
    for (const file of readdirSync(partDir)) {
      if (!file.endsWith(".json")) continue;
      try {
        const filePath = join(partDir, file);
        const content = readFileSync(filePath, "utf-8");
        const part = JSON.parse(content) as StoredPart;
        if (THINKING_TYPES.has(part.type)) {
          unlinkSync(filePath);
          anyRemoved = true;
        }
      } catch {
        continue;
      }
    }
  } catch {
    return false;
  }

  return anyRemoved;
}

// =============================================================================

// =============================================================================

export function findMessageByIndexNeedingThinking(sessionID: string, targetIndex: number): string | null {
  const messages = readMessages(sessionID);

  if (targetIndex < 0 || targetIndex >= messages.length) return null;

  const targetMsg = messages[targetIndex];
  if (!targetMsg || targetMsg.role !== "assistant") return null;

  const parts = readParts(targetMsg.id);
  if (parts.length === 0) return null;

  const sortedParts = [...parts].sort((a, b) => a.id.localeCompare(b.id));
  const firstPart = sortedParts[0];
  if (!firstPart) return null;

  const firstIsThinking = THINKING_TYPES.has(firstPart.type);

  if (!firstIsThinking) {
    return targetMsg.id;
  }

  return null;
}

