// Verifies fetchModels' project-id resolution, routed through the same AntigravityHandleOrchestrator.
// resolveProjectId the serve path uses (driver/javaHandle.ts's resolveProjectIdViaJava): (a) the
// packed-managedProjectId short-circuit never touches the network loader, and (b) a fresh discovery
// persists the discovered managedProjectId into the host account store.
import { describe, it, expect, vi } from "vitest";

vi.mock("../plugin/project.js", () => ({
  loadManagedProject: vi.fn(async () => ({ cloudaicompanionProject: "discovered-proj-1" })),
  onboardManagedProject: vi.fn(async () => undefined),
}));

function fakeManager(account) {
  return {
    mutate(id, fn) {
      const patch = { id, meta: { ...(account.meta || {}) } };
      fn(patch);
      account.meta = patch.meta;
    },
  };
}

describe("resolveProjectIdViaJava", () => {
  it("short-circuits on an already-known managedProjectId (no network)", async () => {
    const { resolveProjectIdViaJava } = await import("../driver/javaHandle.js");
    const { loadManagedProject } = await import("../plugin/project.js");
    const account = { id: "acc1", refresh: "rt-1", meta: { managedProjectId: "existing-project-123" } };
    const projectId = await resolveProjectIdViaJava(fakeManager(account), account, "fake-access", () => {}, undefined);
    expect(projectId).toBe("existing-project-123");
    expect(loadManagedProject).not.toHaveBeenCalled();
  });

  it("discovers + persists a managedProjectId via loadManagedProject when none is known", async () => {
    const { resolveProjectIdViaJava } = await import("../driver/javaHandle.js");
    const { loadManagedProject } = await import("../plugin/project.js");
    const account = { id: "acc2", refresh: "rt-2", meta: {} };
    const manager = fakeManager(account);
    const projectId = await resolveProjectIdViaJava(manager, account, "fake-access", () => {}, undefined);
    expect(projectId).toBe("discovered-proj-1");
    expect(loadManagedProject).toHaveBeenCalled();
    expect(account.meta.managedProjectId).toBe("discovered-proj-1");
    expect(account.meta.syntheticProjectId).toBeTruthy(); // always minted on first use
  });
});
