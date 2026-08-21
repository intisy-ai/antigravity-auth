import { describe, expect, it, vi } from "vitest";

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
  ANTIGRAVITY_SETTINGS_SCHEMA: {},
  RETRY_KEYS: [],
}));

function contextSpy() {
  const provided: Record<string, unknown> = {};
  return {
    provided,
    context: { provide: vi.fn((key: string | { id: string }, value: unknown) => { provided[typeof key === "string" ? key : key.id] = value; }), paths: { home: "/tmp/home" } },
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
  it("provides exactly the provider capability its manifest declares", async () => {
    const plugin = (await import("./plugin.js")).default;
    const { context, provided } = contextSpy();
    await plugin.activate(context as never);
    expect(Object.keys(provided)).toEqual(["provider"]);
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

  it("deactivates without throwing", async () => {
    const plugin = (await import("./plugin.js")).default;
    expect(plugin.deactivate()).toBeUndefined();
  });
});
