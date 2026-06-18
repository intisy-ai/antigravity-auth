// @ts-nocheck
// Custom "Providers" tab for the OpenCode loader TUI (auto-loaded from the repo
// root via the loader's plugin tui-extension scan). Browses each core-auth
// provider's models and edits the Auto meta-model: ranking source + include/
// exclude + (manual) order. Self-contained ESM — reads/writes the same config
// files core-auth uses, so no dependency on the plugin bundle.
import { existsSync, readFileSync, writeFileSync, mkdirSync } from "fs";
import { join } from "path";
import { homedir } from "os";

function configDir() {
  return process.env.HUB_CONFIG_DIR || join(homedir(), ".config", "opencode");
}
function cfgFolder() { return join(configDir(), "config"); }
function readJSON(p, fallback) { try { return JSON.parse(readFileSync(p, "utf8")); } catch (e) { return fallback; } }
function writeJSON(p, obj) { try { if (!existsSync(cfgFolder())) mkdirSync(cfgFolder(), { recursive: true }); writeFileSync(p, JSON.stringify(obj, null, 2), "utf8"); } catch (e) {} }

function modelsCache() { return readJSON(join(cfgFolder(), "core-auth-models.json"), {}); }
function coreConfig() { return readJSON(join(cfgFolder(), "core-auth.json"), {}); }
function saveCoreConfig(cfg) { writeJSON(join(cfgFolder(), "core-auth.json"), cfg); }

function providerIds() { return Object.keys(modelsCache()); }
function catalogRanking(pid) { var e = modelsCache()[pid]; return (e && e.ranking) || []; }
function nameOf(pid, rawId) { var e = modelsCache()[pid]; var m = e && e.models && e.models["antigravity-" + rawId]; return (m && m.name) || rawId; }

var SOURCE_CYCLE = { manual: "recommended", recommended: "leaderboard", leaderboard: "manual" };
var SOURCE_LABEL = { manual: "Manual (your order)", recommended: "Recommended (provider order)", leaderboard: "Leaderboard (Artificial Analysis)" };

// mirror core-auth getAutoConfig reconciliation (kept in sync with config.ts)
function autoConfig(pid) {
  var stored = ((coreConfig().auto || {})[pid]) || {};
  var cat = catalogRanking(pid);
  function reconcile(ids) {
    var out = (Array.isArray(ids) ? ids : []).filter(function (id) { return cat.indexOf(id) >= 0; });
    cat.forEach(function (id) { if (out.indexOf(id) < 0) out.push(id); });
    return out;
  }
  var source = (stored.source === "recommended" || stored.source === "leaderboard") ? stored.source : "manual";
  var order = source === "recommended" ? cat.slice()
    : source === "leaderboard" ? reconcile(stored.leaderboardOrder || [])
    : reconcile(stored.order && stored.order.length ? stored.order : cat);
  var excluded = (Array.isArray(stored.excluded) ? stored.excluded : []).filter(function (id) { return cat.indexOf(id) >= 0; });
  return { order: order, excluded: excluded, source: source };
}
function setAuto(pid, patch) {
  var cfg = coreConfig();
  cfg.auto = cfg.auto || {};
  var prev = cfg.auto[pid] || {};
  cfg.auto[pid] = {
    order: patch.order !== undefined ? patch.order : (prev.order || []),
    excluded: patch.excluded !== undefined ? patch.excluded : (prev.excluded || []),
    source: patch.source !== undefined ? patch.source : (prev.source || "manual"),
    leaderboardOrder: patch.leaderboardOrder !== undefined ? patch.leaderboardOrder : (prev.leaderboardOrder || []),
  };
  saveCoreConfig(cfg);
}

var tab = { mode: "providers", pcur: 0, cur: 0, pid: null };

function render(state, h) {
  if (tab.mode === "auto" && tab.pid) {
    var ac = autoConfig(tab.pid);
    h.pushBody("  " + h.MAGENTA + "#" + h.GRAY + " " + tab.pid + " — Auto model ranking" + h.RST, false);
    var srcSel = tab.cur === 0;
    h.pushBody("  " + (srcSel ? h.YELLOW + "> " + h.RST : "  ") + (srcSel ? h.BG_SEL + h.BOLD + h.WHITE : h.CYAN) + "Ranking source: " + SOURCE_LABEL[ac.source] + h.RST, srcSel);
    h.pushBody("  " + h.DIM + "r cycle source · space include/exclude · u/d move (manual) · Esc back" + h.RST, false);
    h.pushBody("", false);
    ac.order.forEach(function (id, i) {
      var sel = tab.cur === i + 1;
      var inc = ac.excluded.indexOf(id) < 0;
      var box = inc ? "[x]" : "[ ]";
      h.pushBody("  " + (sel ? h.YELLOW + "> " + h.RST : "  ") + (sel ? h.BG_SEL + h.BOLD + h.WHITE : (inc ? h.GRAY : h.DIM)) + box + " " + (i + 1) + ". " + nameOf(tab.pid, id) + h.RST, sel);
    });
    h.pushFoot("  " + h.GRAY + "-".repeat(h.barW) + h.RST);
    h.pushFoot("  " + h.DIM + "^v Move   r Source   Space Toggle   u/d Reorder   Esc Back" + h.RST);
    return;
  }
  var pids = providerIds();
  h.pushBody("  " + h.MAGENTA + "#" + h.GRAY + " Providers (" + pids.length + ")" + h.RST, false);
  if (!pids.length) { h.pushBody("    " + h.GRAY + "No providers yet — sign in via oc auth login." + h.RST, false); }
  pids.forEach(function (pid, i) {
    var sel = tab.pcur === i;
    var count = catalogRanking(pid).length;
    h.pushBody("  " + (sel ? h.YELLOW + "> " + h.RST : "  ") + (sel ? h.BG_SEL + h.BOLD + h.WHITE : h.GRAY) + pid + h.RST + h.DIM + "  (" + count + " model" + (count === 1 ? "" : "s") + ")" + h.RST, sel);
  });
  h.pushFoot("  " + h.GRAY + "-".repeat(h.barW) + h.RST);
  h.pushFoot("  " + h.DIM + "^v Move   Enter Configure Auto   Tab Switch   Q Quit" + h.RST);
}

function handleKey(key, state, tuiApi) {
  if (tab.mode === "auto" && tab.pid) {
    var ac = autoConfig(tab.pid);
    var rows = ac.order.length + 1;   // +1 for the source row
    if (key === "escape" || key === "q") { tab.mode = "providers"; tab.cur = 0; return; }
    if (key === "up" || key === "w") { tab.cur = (tab.cur - 1 + rows) % rows; return; }
    if (key === "down" || key === "s") { tab.cur = (tab.cur + 1) % rows; return; }
    if (tab.cur === 0) {
      if (key === "r" || key === "enter" || key === "space") setAuto(tab.pid, { source: SOURCE_CYCLE[ac.source] });
      return;
    }
    var idx = tab.cur - 1;
    var id = ac.order[idx];
    if (!id) return;
    if (key === "space" || key === "enter") {
      var ex = ac.excluded.slice();
      var at = ex.indexOf(id);
      if (at >= 0) ex.splice(at, 1); else ex.push(id);
      setAuto(tab.pid, { excluded: ex });
      return;
    }
    if ((key === "u" || key === "[") && ac.source === "manual" && idx > 0) {
      var up = ac.order.slice(); var t = up[idx - 1]; up[idx - 1] = up[idx]; up[idx] = t;
      setAuto(tab.pid, { order: up }); tab.cur--; return;
    }
    if ((key === "d" || key === "]") && ac.source === "manual" && idx < ac.order.length - 1) {
      var dn = ac.order.slice(); var t2 = dn[idx + 1]; dn[idx + 1] = dn[idx]; dn[idx] = t2;
      setAuto(tab.pid, { order: dn }); tab.cur++; return;
    }
    return;
  }
  var pids = providerIds();
  if (!pids.length) return;
  if (key === "up" || key === "w") { tab.pcur = (tab.pcur - 1 + pids.length) % pids.length; return; }
  if (key === "down" || key === "s") { tab.pcur = (tab.pcur + 1) % pids.length; return; }
  if (key === "enter" || key === "space") { tab.pid = pids[tab.pcur]; tab.mode = "auto"; tab.cur = 0; return; }
}

export default function (tuiApi) {
  // only attach to the OpenCode loader; the Claude loader ships its own Providers tab
  var app = String(process.env.HUB_APP_NAME || "").toLowerCase();
  if (app.indexOf("claude") >= 0) return;
  tuiApi.registerTab({ id: "providers", label: "Providers", render: render, handleKey: handleKey });
}
