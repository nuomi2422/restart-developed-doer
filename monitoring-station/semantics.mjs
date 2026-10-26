// ============================================================
// semantics.mjs —— 语义资产只读视图（P0-C，81 号 §6）
//
// WHY THIS EXISTS (2026-10-25，用户原话):
//   “他的语义在哪里？我没有在监测台看到。”
//   语义表一直在盘上（config/numen/rdd-semantics/<uuid>.json），但监测台一次都没读过它。
//   结果：规划侧按“这个东西对这个目标重要”办事，人却看不到它凭什么这么判。
//
// 口径（四条，每条都在代码里落点）：
//   ① 只读：不写回、不重新推导 importance —— 语义的真源是需求清单，这里只是窗口；
//      “派生自需求清单”必须有 source 字段为证，缺来源就写 UNKNOWN_SOURCE，不美化成派生。
//   ② 四态分开：目录不存在 / 文件损坏 / 空表 / 正常，各自有状态，不混成“0 条”。
//   ③ 不按前 N 条冒充全量：返回 totalCount，页面负责分页（默认全给，表本身只有几十行）。
//   ④ 过期可见：带 ageSeconds/stale，超过 10 分钟标 STALE —— 语义会随换目标重派生。
//
// USAGE:
//   import { createSemanticsView } from "./semantics.mjs";
//   const view = createSemanticsView(configDir);   // configDir = <gamedir>/config/numen
//   await view.all()                                // GET /api/rdd-semantics
//
// NOTES:
//   * 只读 <configDir>/rdd-semantics 下的 *.json，文件名即 companionId；不做路径拼接猜测。
//   * 单文件上限 512 KiB，超了如实报 TOO_LARGE，不截断冒充完整。
//   * 坏 JSON 报 CORRUPT 并把 parse 错误留在 note 里；绝不 catch 后当空表。
// ============================================================

import { readdir, stat, readFile } from "node:fs/promises";
import { join } from "node:path";

const MAX_BYTES = 512 * 1024;
const STALE_MS = 10 * 60 * 1000;

/** 一条语义记录只保留对外需要的字段；其余原样带出（供以后加字段不丢信息）。 */
function normalizeEntry(raw) {
  if (!raw || typeof raw !== "object") return null;
  const assetKey = typeof raw.assetKey === "string" ? raw.assetKey : "";
  if (!assetKey) return null;
  return {
    assetKey,
    importance: raw.importance ?? "UNKNOWN",
    nature: raw.nature ?? "UNKNOWN",
    servesGoal: raw.servesGoal ?? "",
    source: raw.source ?? "",
    requiredCapability: raw.requiredCapability ?? "",
    substitutes: Array.isArray(raw.substitutes) ? raw.substitutes : [],
  };
}

function toEntries(parsed) {
  if (Array.isArray(parsed)) return parsed;
  if (parsed && Array.isArray(parsed.entries)) return parsed.entries;
  if (parsed && parsed.entries && typeof parsed.entries === "object") return Object.values(parsed.entries);
  if (parsed && typeof parsed === "object") {
    // 形如 { "minecraft:iron_chestplate": {...}, ... }
    const vals = Object.values(parsed);
    if (vals.every((v) => v && typeof v === "object")) return vals;
  }
  return [];
}

export function createSemanticsView(configDir) {
  const dir = join(configDir, "rdd-semantics");

  async function readOne(file) {
    const path = join(dir, file);
    const companionId = file.replace(/\.json$/i, "");
    const st = await stat(path);
    const ageSeconds = Math.max(0, Math.round((Date.now() - st.mtimeMs) / 1000));
    const base = { companionId, file, bytes: st.size, mtime: new Date(st.mtimeMs).toISOString(), ageSeconds, stale: Date.now() - st.mtimeMs > STALE_MS };
    if (st.size > MAX_BYTES) return { ...base, state: "TOO_LARGE", entries: [], note: "file exceeds 512 KiB; refused rather than truncated" };
    let text;
    try { text = await readFile(path, "utf8"); }
    catch (e) { return { ...base, state: "UNREADABLE", entries: [], note: String(e && e.message || e) }; }
    let parsed;
    try { parsed = JSON.parse(text); }
    catch (e) { return { ...base, state: "CORRUPT", entries: [], note: "bad json: " + String(e && e.message || e) }; }
    const entries = toEntries(parsed).map(normalizeEntry).filter(Boolean);
    const unknownSource = entries.filter((x) => !x.source).length;
    return {
      ...base,
      state: entries.length ? "OK" : "EMPTY",
      entries,
      count: entries.length,
      unknownSourceCount: unknownSource,
      note: unknownSource ? "some entries have no source: displayed as UNKNOWN_SOURCE, not as 派生" : "",
    };
  }

  async function all() {
    const generatedAt = new Date().toISOString();
    let files;
    try {
      files = (await readdir(dir)).filter((f) => f.toLowerCase().endsWith(".json")).sort();
    } catch (e) {
      return {
        ok: true,
        state: "NO_DIRECTORY",
        generatedAt,
        dir,
        companions: [],
        totalCount: 0,
        note: "rdd-semantics 目录不存在或不可读 —— 这不是“0 条”，是还没有语义表（未派生过）。",
        detail: String(e && e.message || e),
      };
    }
    if (!files.length) {
      return { ok: true, state: "NO_FILES", generatedAt, dir, companions: [], totalCount: 0, note: "目录存在但没有语义文件（尚未派生）。" };
    }
    const companions = [];
    for (const f of files) {
      try { companions.push(await readOne(f)); }
      catch (e) { companions.push({ companionId: f.replace(/\.json$/i, ""), file: f, state: "STAT_FAILED", entries: [], note: String(e && e.message || e) }); }
    }
    const totalCount = companions.reduce((n, c) => n + (c.entries ? c.entries.length : 0), 0);
    const bad = companions.filter((c) => c.state !== "OK" && c.state !== "EMPTY").length;
    const state = bad === 0 ? "OK" : (bad === companions.length ? "BROKEN" : "PARTIAL");
    return { ok: true, state, generatedAt, dir, companions, totalCount, schemaVersion: 1 };
  }

  return { dir, all };
}
