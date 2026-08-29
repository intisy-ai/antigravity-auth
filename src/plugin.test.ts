import { describe, expect, it, vi } from "vitest";
import { providerSupport } from "@intisy-ai/basekit/auth";

const handleIr = vi.fn(async (_request: unknown, context: { provider?: string }) => ({ lane: context.provider }));

vi.mock("./driver/index.js", () => ({
  driver: {
    id: "antigravity",
    label: "Antigravity",
    models: {},
    handleIr,
    loginFlow: async () => ({ url: "", complete: async () => null }),
    geminiCliProviderId: "gemini-cli",
    geminiCliLabel: "Gemini CLI",
    geminiCliModels: {},
    settings: {},
  },
  ANTIGRAVITY_SETTINGS_SCHEMA: [],
  RETRY_KEYS: [],
}));

// The host's own service, which is where the provider helpers come from now. A test supplies the
// real one, so what it exercises is what a loader hands over rather than a stand-in for it.
function contextSpy(services: Record<string, unknown> = { "provider-support": providerSupport() }) {
  const provided: Record<string, unknown> = {};
  return {
    provided,
    context: {
      provide: vi.fn((key: string | { id: string }, value: unknown) => { provided[typeof key === "string" ? key : key.id] = value; }),
      // The engine mints a typed key from an id alone, which is all the plugin needs from it here.
      capability: (id: string) => ({ id }),
      service: (id: string) => ({ id }),
      services: { get: (key: { id: string }) => services[key.id] },
      paths: { home: "/tmp/home" },
    },
  };
}

async function capability() {
  const plugin = (await import("./plugin.js")).default;
  const { context, provided } = contextSpy();
  await plugin.activate(context as never);
  return provided.provider as {
    id: string;
    handleIr: (r: unknown, c: unknown) => Promise<unknown>;
    providers: () => Promise<Array<{ id: string; label: string; accountPool?: string }>>;
  };
}

describe("the antigravity-auth api plugin", () => {
  it("provides exactly the capabilities its manifest declares", async () => {
    const plugin = (await import("./plugin.js")).default;
    const { context, provided } = contextSpy();
    await plugin.activate(context as never);
    expect(Object.keys(provided).sort()).toEqual(["provider", "settings"]);
  });

  it("advertises both lanes, the metered pool first", async () => {
    expect((await (await capability()).providers()).map((lane) => lane.id)).toEqual(["antigravity", "gemini-cli"]);
  });

  it("gives both lanes the same account pool, so one login serves both", async () => {
    const lanes = await (await capability()).providers();
    expect(lanes.map((lane) => lane.accountPool)).toEqual(["antigravity", "antigravity"]);
  });

  it("passes the resolved provider id through, because it is what selects the lane", async () => {
    const ctx = { configDir: "/tmp/home", log: () => {}, model: "m", provider: "gemini-cli" };
    await expect((await capability()).handleIr({ model: "m" }, ctx)).resolves.toEqual({ lane: "gemini-cli" });
  });

  // A host that offers no provider support cannot run a provider at all, and naming the service is
  // the only way an operator learns which host is at fault.
  it("names the missing service rather than leaving the capability unprovided", async () => {
    const plugin = (await import("./plugin.js")).default;
    const { context } = contextSpy({});
    await expect(async () => plugin.activate(context as never)).rejects.toThrow(/provider-support/);
  });

  it("deactivates without throwing", async () => {
    const plugin = (await import("./plugin.js")).default;
    expect(plugin.deactivate()).toBeUndefined();
  });
});
