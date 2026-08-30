import {
  existsSync,
  readFileSync,
  writeFileSync,
  appendFileSync,
} from "node:fs";
import { join } from "node:path";
import { createLogger } from "./logger.js";

const log = createLogger("storage");

/**
 * Files/directories that should be gitignored in the config directory.
 * These contain sensitive data or machine-specific state.
 */
export const GITIGNORE_ENTRIES = [
  "antigravity-accounts.json",
  "antigravity-accounts.json.*.tmp",
  "antigravity-signature-cache.json",
  "antigravity-logs/",
];

/**
 * Ensures a .gitignore file exists in the config directory with entries
 * for sensitive files. Creates the file if missing, or appends missing
 * entries if it already exists.
 */
export function ensureGitignoreSync(configDir: string): void {
  const gitignorePath = join(configDir, ".gitignore");

  try {
    let content: string;
    let existingLines: string[] = [];

    if (existsSync(gitignorePath)) {
      content = readFileSync(gitignorePath, "utf-8");
      existingLines = content.split(/\r?\n/).map((line) => line.trim());
    } else {
      content = "";
    }

    const missingEntries = GITIGNORE_ENTRIES.filter(
      (entry) => !existingLines.includes(entry),
    );

    if (missingEntries.length === 0) {
      return;
    }

    if (content === "") {
      writeFileSync(gitignorePath, missingEntries.join("\n") + "\n", "utf-8");
      log.info("Created .gitignore in config directory");
    } else {
      const suffix = content.endsWith("\n") ? "" : "\n";
      appendFileSync(
        gitignorePath,
        suffix + missingEntries.join("\n") + "\n",
        "utf-8",
      );
      log.info("Updated .gitignore with missing entries", {
        added: missingEntries,
      });
    }
  } catch {
    // best-effort; a missing .gitignore is not fatal
  }
}
