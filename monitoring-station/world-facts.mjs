// ============================================================
// world-facts.mjs —— 单一世界 · 环境事实台（第三批 T3-01 / N1）
// ============================================================
//
// 〔这一页在看什么〕
//   「世界是什么样」这件事，**应该只有一份**。现在这一份散在四个文件里：
//     config/numen/rdd-assets/<uuid>.json    世界事实本体（结构/村庄/机器…）
//     config/numen/rdd-facts/<uuid>.json     任务链血缘（阶段/小步是怎么确认的）
//     config/numen/rdd-history/<uuid>.json  资产状态历史（CURRENT / LOST）
//     config/numen/rdd-ledger/<uuid>.json   死亡台账（丢了什么、有没有证据）
//   本页把四份拼成**一个事实视图**，并逐条回答 N1 契约里要求的那些问题现在答不答得上。
//
// ────────────────────────────────────────────────────────────
// ★★★ 本页的纪律
//
// 〔1〕**接不上就写「未接入」，并且写清「现在拿什么代替」。**
//   N1 契约要求事实带 `confidence`（可信度）与 `coverage`（覆盖范围），
//   实测 `rdd-assets` 的 `value` 里**这两个字段不存在**。本页不说「可信度高」，
//   而是说「没有这个字段，现在用 status=OBSERVED 近似」—— 这两句话对读的人
//   意义完全不同。
//
// 〔2〕**「未接入」是运行时探出来的，不是写死的。**
//   `WIRING` 里每一条都带一个 `probe`，请求时**真的去看数据里有没有那个字段**。
//   所以后来的 AI 只要把字段接上，这一页会**自动**从「未接入」翻成「已接入」，
//   一行渲染代码都不用改。反过来，写死的状态一定会骗人。
//
// 〔3〕**事实年龄用 observedAt 算，不是用文件 mtime。**
//   mtime 是「这份 JSON 什么时候写的」，observedAt 是「这条事实什么时候观测到的」。
//   两者在轮转/缓存场景下差很远 —— 把 mtime 当新鲜度就是我厂反复踩的那条坑。
//
// 〔4〕**过期不当当前。** 超过阈值的事实单独标 `stale`，不与「刚观测到」混在一栏。
//   阈值 `STALE_SECONDS` 可配；未知一律显示「?」，不显示 0。
// ============================================================

import { readdir, readFile, stat } from "node:fs/promises";
import { join, resolve } from "node:path";
import { readJsonlWindow } from "./tail-jsonl.mjs";
import { freshnessOf } from "./freshness.mjs";

/**
 * 同伴名字提示：唯一带名字的地方是死因文本（`deathCauseMsg: "DDD被骷髅射杀"`）。
 * ★ 引擎**任何地方都不存注册名**（binding.json 只有 provider、stats.json 只有
 *   token 台账），所以这只能是**提示**。页面必须标出来，不能当注册名渲染。
 *   与 experiment-stats.mjs 里那份是同一套判据。
 */
async function inferNameHints(MONITOR_DIR) {
  const out = {};
  let win = null;
  try { win = await readJsonlWindow(join(MONITOR_DIR, "instrumentation.jsonl"), 2 * 1024 * 1024, 2000); }
  catch { return out; }
  const votes = new Map();
  for (const e of (win.events || [])) {
    if (e.type !== "death") continue;
    const d = e.data || e.payload || {};
    const cid = d.companionId || d.companion_id || "";
    if (!cid) continue;
    const m = String(d.deathCauseMsg || "").match(/^(.{1,16}?)[被死于杀死淹烧摔]/);
    if (!m) continue;
    const name = m[1].trim();
    if (!name || /^(他|她|它|我|你|该同伴|other|unknown|)$/i.test(name)) continue;
    if (!votes.has(cid)) votes.set(cid, new Map());
    votes.get(cid).set(name, (votes.get(cid).get(name) || 0) + 1);
  }
  for (const [cid, names] of votes) {
    let best = null, bestN = 0;
    for (const [n, c] of names) if (c > bestN) { best = n; bestN = c; }
    if (best) out[cid] = best;
  }
  return out;
}

/** 服务端缓存：这页要读 4 组文件 × 6 只同伴。 */
const CACHE_MS = 12000;

/** 事实年龄超过这个秒数就标「旧」（N1：过期不当当前）。 */
const STALE_SECONDS = 6 * 3600;

/** 事实清单最多列多少条（其余只进统计；**必须报出被丢的条数**）。 */
const MAX_FACT_ROWS = 120;

/**
 * 接线契约 —— 后来的 AI 只需要让 probe 返回 true（通常就是字段接上了），
 * 这一页会自己翻成「已接入」。**不要在这里写死状态。**
 *
 * owner 对应架构包号：N1 共同事实 / N2 语义资产与丢失 / N3 需求与机会 / N6 机器描述。
 */
const WIRING = [
  {
    id: "fact-confidence", owner: "N1", name: "事实可信度",
    need: "每条事实带 confidence（至少区分 OBSERVED / INFERRED / UNKNOWN）",
    stand: "现在用 observation.status（OBSERVED / …）近似；没有独立的置信度字段",
    verify: "随机抽一条事实，问「这是亲眼看到的还是推出来的」——现在答不出来",
    // ★ probe 收到的是**整个探针上下文**，不是单条事实。而且原文（value）已经
    //   因为体积纪律被剔出响应了，所以这里传的是「value 里出现过哪些键」——
    //   判据只需要键名，不需要整份原文。
    //   （第一版写成 `(f) => f.value…` 而 probe 收到的是 ctx，于是永远读到 undefined，
    //     这一条就永远翻不了绿。）
    probe: (ctx) => (ctx.valueKeys || new Set()).has("confidence"),
  },
  {
    id: "fact-coverage", owner: "N1", name: "事实覆盖范围",
    need: "每条事实说明它覆盖多大范围（整个维度？某个区域？某只同伴？）",
    stand: "现在只有 asset.scope（多为 GLOBAL），它是**作用域**不是**覆盖度**",
    verify: "问「这条事实覆盖多大一片」——现在只能答 GLOBAL 或空",
    probe: (ctx) => (ctx.valueKeys || new Set()).has("coverage"),
  },
  {
    id: "fact-tri-state", owner: "N1", name: "已知 / 未知 / 过期 三态",
    need: "把「没见过」与「见过且是 0」与「以前见过现在过期」分成三态",
    stand: "现在只有 status 与 observedAt；「没见过」直接不出现，没有显式的 UNKNOWN",
    verify: "问「某处有没有钻石」与「那处确定没有钻石」——现在这两种答不出来",
    probe: (f) => !!(f.value && typeof f.value.knowledge === "string"),
  },
  {
    id: "fact-projections", owner: "N1", name: "三级投影（简要 / 任务相关 / 详细）",
    need: "按消费者投影，一份存储出三种精度视图，而不是存三份",
    stand: "现在只有一种：`numen_context` 里整段 `known_world_assets` 文本注入给执行 AI",
    verify: "问「这次注入多长」——实测每轮注入整段文本，没有分级",
    probe: () => false,   // ★ 还没有任何投影层；接上后改这里
  },
  {
    id: "asset-purpose", owner: "N2", name: "资产用途语义",
    need: "每项资产带 purpose（为哪件事准备的）、归属、替代品与依赖",
    // stand 写成函数：措辞跟着实测数据走。写死「实测全是 UNKNOWN」这种话，
    // 哪天数据变了页面就在骗人 —— 本工程吃过好几次这种亏。
    stand: (ctx) => {
      const n = ctx.historyRows.length;
      const unknown = ctx.historyRows.filter((h) => !h.purpose || h.purpose === "UNKNOWN").length;
      return "rdd-history 的 purpose：实测 " + n + " 条里有 " + unknown
        + " 条是 UNKNOWN" + (n && unknown === n ? "（全部）" : "") + "；其余字段没有归属/替代品/依赖。";
    },
    verify: "问「这把石镐是为了哪件事准备的」——现在只能答 UNKNOWN",
    probe: (ctx) => ctx.historyRows.some((h) => h.purpose && h.purpose !== "UNKNOWN"),
  },
  {
    id: "asset-loss-confirm", owner: "N2", name: "死亡丢失的确认",
    need: "转存/合成/正常消耗/暂时看不到都**不算丢失**；要确认丢了才算",
    stand: (ctx) => {
      const n = ctx.ledgerRows.length;
      const v = ctx.ledgerRows.filter((l) => l.verified).length;
      const withEvidence = ctx.ledgerRows.filter((l) => l.evidence).length;
      return "rdd-ledger：实测 " + n + " 次死亡里 " + v + " 次已有终态（不是 UNVERIFIED），"
        + withEvidence + " 次带 evidence；其余仍是 UNVERIFIED 且 evidence 为空。";
    },
    verify: "问「这次死亡到底丢了什么」——UNVERIFIED 的那些答不出来",
    // ★ 判据必须是「真的被确认过」：state 明确是已验证态**且** evidence 非空。
    //   只看 state !== 'UNVERIFIED' 会被「未标注」这种兜底值骗成 true ——
    //   写这条的第一版就踩了，页面上把没接上的东西显示成「已接入」。
    probe: (ctx) => ctx.ledgerRows.some((l) => l.verified === true),
  },
  {
    id: "asset-loss-importance", owner: "N2", name: "丢失重要度累计 → 分级重规划",
    need: "确认丢失后累计重要度，到不同阈值给出：无调整 / 局部补充 / 当前目标重规划 / 阶段重规划",
    stand: "结构上没有这一层；死亡目前只触发提醒与掉装回收支线，没有重要度也没有分级",
    verify: "问「丢了关键资产之后系统打算怎么办」——现在没有分级建议",
    probe: () => false,
  },
  {
    id: "goal-requirement", owner: "N3", name: "一级目标声明需求 + 机会提前获取",
    need: "生成一级目标时标出大目标需要什么；发现真实可达的机会时可提前取",
    stand: "现在只有阶段/小步的需求描述；没有「机会 → 候选」的匹配层",
    verify: "问「旁边箱子里那颗钻石能不能现在就顺路拿」——现在没有这个判断",
    probe: () => false,
  },
  {
    id: "machine-descriptor", owner: "N6", name: "机器描述与设计态/运行态分离",
    need: "记录需要的实体/液体/功能区/操作顺序，并把「设计需要」与「实地观测到」分开",
    stand: "事实表里有 world_* 类型，但没有机器描述；也分不清设计与实测",
    verify: "问「这台刷铁机现在算建好了还是算能用了」——现在答不出",
    probe: () => false,
  },
];

function isoOrNull(v) {
  return v == null ? null : new Date(v).toISOString();
}
function num(v) {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}
function parseTs(s) {
  const t = Date.parse(s);
  return Number.isFinite(t) ? t : null;
}
function shortId(id) {
  return id ? String(id).slice(0, 8) : "?";
}
/** 截断长文本并标出来 —— 截了要说，不许让读的人以为那就是全文。 */
function trimText(s, n) {
  if (typeof s !== "string") return null;
  if (s.length <= n) return s;
  return s.slice(0, n) + "…";
}

/** 读一个目录下的全部 <uuid>.json。读不到就当没有（并说出来），不当空。 */
async function readPerCompanion(dir, nowMs) {
  const out = { readable: true, missing: false, files: [], byCompanion: new Map(), error: null };
  let names = [];
  try { names = await readdir(dir); }
  catch {
    out.readable = false;
    out.missing = true;
    out.error = "读不到目录 " + dir;
    return out;
  }
  const picked = names.filter((n) => /^[0-9a-fA-F-]{8,}\.json$/.test(n));
  for (const n of picked) {
    const p = join(dir, n);
    let raw = null, mtimeMs = null;
    try {
      raw = await readFile(p, "utf8");
      mtimeMs = (await stat(p)).mtimeMs;
    } catch (e) {
      out.files.push({ file: n, unreadable: true, error: String(e.message) });
      continue;
    }
    let o = null;
    try { o = JSON.parse(raw); } catch (e) {
      out.files.push({ file: n, unreadable: true, error: "JSON 解析失败" });
      continue;
    }
    const id = n.replace(/\.json$/, "");
    out.files.push({ file: n, bytes: raw.length, mtime: isoOrNull(mtimeMs), secondsSinceWrite: Math.round((nowMs - mtimeMs) / 1000) });
    out.byCompanion.set(id, o);
  }
  if (!picked.length) { out.readable = false; out.missing = true; out.error = "目录里没有 <uuid>.json"; }
  return out;
}

export function createWorldFactsView(MONITOR_DIR) {
  const CONFIG_DIR = resolve(MONITOR_DIR, "..");
  let cache = null;

  return {
    async all() {
      const nowMs = Date.now();
      if (cache && nowMs - cache.at < CACHE_MS) return cache.data;

      const sources = {
        assets: await readPerCompanion(join(CONFIG_DIR, "rdd-assets"), nowMs),
        lineage: await readPerCompanion(join(CONFIG_DIR, "rdd-facts"), nowMs),
        history: await readPerCompanion(join(CONFIG_DIR, "rdd-history"), nowMs),
        ledger: await readPerCompanion(join(CONFIG_DIR, "rdd-ledger"), nowMs),
      };

      // ── 事实本体（rdd-assets）────────────────────────────────
      // ★ 一条 asset 记录 = 一个 assetId + 它的最新 observation。
      //   注意它**没有** companionId 字段（文件路径带 uuid 才对应同伴）——
      //   「资产属于谁」只能靠文件名，这也说明「归属」是文件约定而不是数据里的事实。
      const facts = [];
      const valueKeys = new Set();
      let factsTotal = 0, factsBadShape = 0;
      for (const [cid, arr] of sources.assets.byCompanion) {
        if (!Array.isArray(arr)) { factsBadShape += 1; continue; }
        for (const a of arr) {
          factsTotal += 1;
          const ob = a && a.observation ? a.observation : null;
          const v = (ob && ob.value) || {};
          const observedAt = num(ob && ob.observedAt);
          facts.push({
            companionId: cid,
            companionFrom: "文件名",          // ★ 归属来源要标出来
            assetId: (a && a.assetId) || "?",
            type: (ob && ob.type) || "未标注类型",
            source: (ob && ob.source) || "未标注来源",
            environmentId: (ob && ob.environmentId) || v.dimension || null,
            status: (a && a.status) || "未标注状态",
            scope: (a && a.scope) || null,
            kind: v.kind || null,
            label: v.label || null,
            dimension: v.dimension || null,
            x: num(v.x), y: num(v.y), z: num(v.z),
            refreshPolicy: v.refresh_policy || null,
            summary: v.summary ? trimText(v.summary, 160) : null,
            valueState: v.state || null,
            resources: v.resources && typeof v.resources === "object" ? v.resources : null,
            observedAt: observedAt,
            observedAgeSeconds: observedAt == null ? null : Math.round((nowMs - observedAt) / 1000),
            stale: observedAt == null ? null : (nowMs - observedAt) / 1000 > STALE_SECONDS,
            // ★ 只带**已经归一化出来的字段**，不带 value / observation 原文 ——
            //   那两份是同一份数据，留着会让 payload 直接翻三倍（实测 120 条就 200KB）。
            //   要看原文的人去 config/numen/rdd-assets/<uuid>.json。
            resourceSummary: v.resources && typeof v.resources === "object"
              ? Object.entries(v.resources).map(([k, n]) => k + "=" + n).join(" ") : null,
          });
          // value 里出现过哪些键 —— 只留键名，供接线判据用（见 fact-confidence 的注释）
          for (const k of Object.keys(v)) valueKeys.add(k);
        }
      }
      facts.sort((a, b) => (b.observedAt || 0) - (a.observedAt || 0));

      // ── 资产状态历史（rdd-history）───────────────────────────
      const historyRows = [];
      for (const [cid, arr] of sources.history.byCompanion) {
        if (!Array.isArray(arr)) continue;
        for (const h of arr) {
          const lastSeen = num(h && h.lastSeenMillis);
          historyRows.push({
            companionId: cid,
            assetId: (h && h.assetId) || "?",
            purpose: (h && h.purpose) || null,
            state: (h && h.state) || "未标注状态",
            lastCount: num(h && h.lastCount),
            dimension: (h && h.dimension) || null,
            x: num(h && h.x), y: num(h && h.y), z: num(h && h.z),
            lastSeenMillis: lastSeen,
            lastSeenAgeSeconds: lastSeen == null ? null : Math.round((nowMs - lastSeen) / 1000),
          });
        }
      }
      historyRows.sort((a, b) => (b.lastSeenMillis || 0) - (a.lastSeenMillis || 0));

      // ── 死亡台账（rdd-ledger）───────────────────────────────
      const ledgerRows = [];
      for (const [cid, o] of sources.ledger.byCompanion) {
        const deaths = o && Array.isArray(o.deaths) ? o.deaths : [];
        for (const d of deaths) {
          const wall = num(d && d.wallClockMillis);
          ledgerRows.push({
            companionId: cid,
            seq: num(d && d.seq),
            gameTime: num(d && d.gameTime),
            wallClockMillis: wall,
            deathAt: (d && d.deathAt) || null,
            lostEntries: num(d && d.lostEntries),
            // ★ state 实测全是 UNVERIFIED；evidence 是空串。
            //   所以「这次死亡丢了什么」现在**没有可信答案** —— 页面必须这么写。
            state: (d && d.state) || "未标注",
            evidence: (d && d.evidence) || "",
            verified: !!((d && d.state) && d.state !== "UNVERIFIED"),
          });
        }
      }
      ledgerRows.sort((a, b) => (b.wallClockMillis || 0) - (a.wallClockMillis || 0));

      // ── 血缘（rdd-facts）───────────────────────────────────
      const lineage = [];
      for (const [cid, o] of sources.lineage.byCompanion) {
        if (!o || typeof o !== "object") continue;
        for (const kind of ["stages", "subtasks"]) {
          const arr = Array.isArray(o[kind]) ? o[kind] : [];
          for (const x of arr) {
            lineage.push({
              companionId: cid,
              kind,
              lineageId: x.lineageId || null,
              goalId: x.goalId || null,
              objective: x.objective || null,
              stageKey: x.stageKey || null,
              text: trimText(x.rawStage || x.rawSubtask, 400),
              at: num(x.at),
              // ★ evidence 实测是 "primary confirmed" —— 这是事实**怎么来的**，
              //   不是事实**有多可信**。两者别混。
              evidence: x.evidence || null,
              version: num(o.version),
            });
          }
        }
      }
      lineage.sort((a, b) => (b.at || 0) - (a.at || 0));

      // ── 分级统计 ──────────────────────────────────────────
      function tally(rows, field) {
        const m = new Map();
        for (const r of rows) {
          const k = r[field] == null || r[field] === "" ? "（未标注）" : String(r[field]);
          m.set(k, (m.get(k) || 0) + 1);
        }
        return [...m.entries()].map(([key, count]) => ({ key, count })).sort((a, b) => b.count - a.count);
      }
      function ageTally(rows) {
        // ★ 三个桶必须分清：未知 / 新鲜 / 旧。**未知不许塞进「旧」**
        const out = { unknown: 0, fresh: 0, stale: 0 };
        for (const r of rows) {
          if (r.observedAgeSeconds == null) { out.unknown += 1; continue; }
          if (r.observedAgeSeconds > STALE_SECONDS) out.stale += 1; else out.fresh += 1;
        }
        return out;
      }

      const ages = ageTally(facts);
      const grades = {
        byType: tally(facts, "type"),
        byKind: tally(facts, "kind"),
        byDimension: tally(facts, "dimension"),
        byStatus: tally(facts, "status"),
        byRefreshPolicy: tally(facts, "refreshPolicy"),
        bySource: tally(facts, "source"),
        byAge: [
          { key: "刚观测到（< " + Math.round(STALE_SECONDS / 3600) + " 小时）", count: ages.fresh },
          { key: "已过期（> " + Math.round(STALE_SECONDS / 3600) + " 小时）", count: ages.stale },
          { key: "年龄未知（没有 observedAt）", count: ages.unknown },
        ],
      };

      // ── 接线契约：**运行时探测**（纪律〔2〕）─────────────────
      const probeCtx = { historyRows, ledgerRows, facts, lineage, valueKeys };
      const wiring = WIRING.map((w) => {
        let connected = false;
        let probeError = null;
        try { connected = !!w.probe(probeCtx); } catch (e) { probeError = String(e.message); }
        let stand = null;
        try { stand = typeof w.stand === "function" ? w.stand(probeCtx) : w.stand; }
        catch (e) { stand = "（描述取数失败：" + String(e.message) + "）"; }
        return {
          id: w.id, owner: w.owner, name: w.name,
          need: w.need, stand: stand, verify: w.verify,
          state: connected ? "connected" : "missing",
          connected, probeError,
        };
      });

      const coverage = {
        sources: [
          { key: "assets", label: "世界事实本体", file: "config/numen/rdd-assets/<uuid>.json", ...sourceBrief(sources.assets) },
          { key: "lineage", label: "任务链血缘", file: "config/numen/rdd-facts/<uuid>.json", ...sourceBrief(sources.lineage) },
          { key: "history", label: "资产状态历史", file: "config/numen/rdd-history/<uuid>.json", ...sourceBrief(sources.history) },
          { key: "ledger", label: "死亡台账", file: "config/numen/rdd-ledger/<uuid>.json", ...sourceBrief(sources.ledger) },
        ],
        factsTotal,
        factsListed: Math.min(facts.length, MAX_FACT_ROWS),
        factsDropped: Math.max(0, facts.length - MAX_FACT_ROWS),
        factsBadShape,
        historyTotal: historyRows.length,
        ledgerTotal: ledgerRows.length,
        lineageTotal: lineage.length,
        note_zh: "每只同伴一份 JSON。**同伴归属来自文件名，不是数据里的字段** —— 这一点在页面上标出来了。",
      };

      // 新鲜度：拿「四个来源里最新写的那一份」当参照
      let newestMs = null, newestFile = null;
      for (const k of Object.keys(sources)) {
        for (const f of sources[k].files) {
          if (!f.mtime) continue;
          const ms = Date.parse(f.mtime);
          if (newestMs == null || ms > newestMs) { newestMs = ms; newestFile = sources[k].files.find((x) => x.mtime === f.mtime).file; }
        }
      }
      const anyReadable = sources.assets.readable || sources.lineage.readable
        || sources.history.readable || sources.ledger.readable;
      const worst = !anyReadable ? "unreadable"
        : (Object.keys(sources).some((k) => !sources[k].readable) ? "old_while_game_alive" : "fresh");
      const VERDICT_ZH = {
        unreadable: "★ 四个事实来源一个都读不到 —— 下面的数字全部无效",
        old_while_game_alive: "★ 有事实来源读不到 —— 页面上标了「未接入」的那几块是真的没有数据",
        fresh: "四个事实来源都读到了",
      };
      const freshness = Object.assign(
        { readable: anyReadable, per_source: coverage.sources.map((s) => ({ key: s.key, readable: s.readable })) },
        (await freshnessOf({
          monitorDir: CONFIG_DIR,
          fileName: newestFile ? join("rdd-assets", newestFile) : "rdd-assets",
          nowMs,
        }).catch(() => null)) || {}
      );
      freshness.verdict = worst;
      freshness.verdict_zh = VERDICT_ZH[worst];

      const emptyReasons = [];
      if (!facts.length) emptyReasons.push("没有读到任何世界事实 —— rdd-assets 要么不存在，要么里面不是数组。");
      if (!historyRows.length) emptyReasons.push("没有读到资产状态历史（rdd-history）—— 「这项资产还在不在」现在答不出来。");
      if (!ledgerRows.length) emptyReasons.push("没有读到死亡台账（rdd-ledger）—— 「这次死亡丢了什么」现在答不出来。");
      if (!lineage.length) emptyReasons.push("没有读到任务链血缘（rdd-facts）。");

      // ── 同伴名单（含名字提示）─────────────────────────────
      // ★ 名字提示只能从死因文本推（实测引擎任何地方都不存注册名：
      //   binding.json 只有 provider、stats.json 只有 token 台账）。
      //   与「⑥ 实验统计」同一套做法，页面上标出来它是**提示**不是注册名。
      const nameHints = await inferNameHints(MONITOR_DIR);
      const perCompanion = new Map();
      for (const [cid] of sources.assets.byCompanion) perCompanion.set(cid, { id: cid, facts: 0, newestMs: null });
      for (const f of facts) {
        const p = perCompanion.get(f.companionId);
        if (!p) continue;
        p.facts += 1;
        if (f.observedAt != null && (p.newestMs == null || f.observedAt > p.newestMs)) p.newestMs = f.observedAt;
      }
      const companions = [...perCompanion.entries()].map(([cid, p]) => ({
        id: cid,
        nameHint: nameHints[cid] || null,
        facts: p.facts,
        newestFactAt: isoOrNull(p.newestMs),
        hasAssetsFile: true,
        hasHistoryFile: sources.history.byCompanion.has(cid),
        hasLedgerFile: sources.ledger.byCompanion.has(cid),
        hasLineageFile: sources.lineage.byCompanion.has(cid),
      })).sort((a, b) => (b.newestFactAt || "").localeCompare(a.newestFactAt || ""));

      const data = {
        ok: true,
        generatedAt: isoOrNull(nowMs),
        monitorDir: MONITOR_DIR,
        configDir: CONFIG_DIR,
        staleSeconds: STALE_SECONDS,
        newestWriteAt: isoOrNull(newestMs),
        companions,
        facts: facts.slice(0, MAX_FACT_ROWS),
        history: historyRows,
        ledger: ledgerRows,
        lineage: lineage.slice(0, 80),
        grades,
        wiring,
        wiringSummary: {
          connected: wiring.filter((w) => w.connected).length,
          total: wiring.length,
          note_zh: "这一列是**运行时探测**出来的：页面真的去数据里找那个字段，找不到就写「未接入」。"
            + "后来的 AI 把字段接上，这一行会自动翻成「已接入」，一行渲染代码都不用改。",
        },
        coverage,
        freshness,
        caveats: [
          { id: "ownership-from-filename", note_zh: "世界事实**没有 companionId 字段** ——「这条事实属于哪只同伴」来自文件名约定。本页把它标成「文件名」，别当成数据里记录的事实。" },
          { id: "no-confidence-field", note_zh: "N1 要求事实带 confidence 与 coverage，实测 value 里**两个字段都没有**。本页不说「可信度高」，只说「没有这个字段，现在用 status 近似」。" },
          { id: "evidence-is-not-confidence", note_zh: "rdd-facts 的 evidence（实测 \"primary confirmed\"）说的是这条**怎么来的**，不是它**有多可信**；两者不要混。" },
          { id: "observed-at-not-mtime", note_zh: "事实年龄用 observation.observedAt 算，**不是**文件 mtime —— mtime 是「这份 JSON 什么时候写的」，两者在轮转/缓存下差很远。" },
{ id: "purpose-mostly-unknown", note_zh: "rdd-history 的 purpose 绝大多数是 UNKNOWN ⇒ 资产的「用途语义」这一层基本还没有（具体条数见接线表，那一条的措辞跟着数据走）。" },
    { id: "loss-seldom-verified", note_zh: "rdd-ledger 里多数死亡仍是 UNVERIFIED、evidence 为空 ⇒ **大部分死亡没有被确认过丢了什么**；少数已有终态，但「累计重要度 → 分级重规划」整层还没有。" },
          { id: "not-world-history", note_zh: "这一页是**当前观测**，不是世界编年史；文件里没有的地点就是「没记录过」，不等于「那里没有」。" },
        ],
        emptyReasons,
      };
      cache = { at: nowMs, data };
      return data;
    },
  };
}

function sourceBrief(s) {
  return {
    readable: s.readable,
    missing: s.missing,
    error: s.error,
    files: s.files.length,
    companions: s.byCompanion.size,
    unreadable: s.files.filter((f) => f.unreadable).length,
    newestSeconds: s.files.length ? Math.min(...s.files.filter((f) => f.secondsSinceWrite != null).map((f) => f.secondsSinceWrite).concat([Infinity])) : null,
  };
}