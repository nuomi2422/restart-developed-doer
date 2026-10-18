// ============================================================
// economy.mjs —— 经济与开关（第三批 T3-07 / N5 ＋ 72 号经营脚本第一批）
// ============================================================
//
// 〔这一页在看两个东西，它们都不是「钱」〕
//
//   A. **经营侧（已有真实数据）**：ACX 现在有哪些脚本、跑了多少次、
//      成败怎么分布、以及**产量到底记没记**。
//      真实来源：`config/numen/acx/library.json`（脚本定义）、
//      `config/numen/acx/lib/stable/*.ac`（磁盘上的脚本文件）、
//      `config/numen/acx/records.jsonl`（落盘运行记录）、
//      `monitor/acx.jsonl`（运行事件流）。
//
//   B. **配置侧（几乎全是骨架）**：能不能关模块、能不能限经验注入、
//      能不能禁目标、热切换怎么判、坏配置怎么办。
//      这就是 T3-07「经济/开关装置」，目前**结构上还没有**。
//
// ────────────────────────────────────────────────────────────
// ★★★ 本页的纪律
//
// 〔1〕**「records.jsonl 里没有 SUCCESS」不等于「没有成功运行」。**
//   实测 records.jsonl 只落 PAUSED / FAIL / TIMEOUT（101 条里 0 条 SUCCESS）。
//   所以「成功率」必须从 `monitor/acx.jsonl` 的 RUN_FINISHED 算，
//   而**两个来源不能相加**（那是同一批运行的两个视图）。
//   页面把这两种口径分区摆放，并各自标来源。
//
// 〔2〕**产量指标整层未接入，且证据摆在页面上。**
//   `records.jsonl` 每条都有 `progress` 字段，但实测**恒为 `{}`** ——
//   所以 AcxProgress 的 11 个字段（mined/found/collected/placed/smelted/
//   gathered/moved/removed/progress/count/gained）一个都没有真实读数。
//   面板逐个字段列出来并标「未接入」，而不是画一条空的产量曲线。
//
// 〔3〕**72 号八个经营脚本：库里一个都没有，这是事实不是结论。**
//   roster 逐个列出（含机制假设与阻塞原因），状态一律「未实现」，
//   由**运行时探测**决定有没有出现 —— 哪天脚本接上了，这一行自动变。
//
// 〔4〕**经济配置的判据是「文件里真有那个键」**，不是「有个开关变量」。
//   候选文件名全都探测一遍；一个都没读到 ⇒ 整块「未接入」，
//   且**不显示任何默认值**（默认值是我编的，不是系统里的）。
// ============================================================

import { readdir, readFile, stat } from "node:fs/promises";
import { join, resolve } from "node:path";
import { readJsonlWindow } from "./tail-jsonl.mjs";
import { freshnessOf } from "./freshness.mjs";

const CACHE_MS = 12000;

/** records.jsonl 的读取上限（实测 54KB，给足）。 */
const RECORDS_MAX_BYTES = 4 * 1024 * 1024;
const RECORDS_MAX_EVENTS = 2000;
const ACX_EVENT_MAX_BYTES = 4 * 1024 * 1024;
const ACX_EVENT_MAX_EVENTS = 20000;
/** 脚本台账最多列多少个（其余只进统计，被丢的必须报数）。 */
const MAX_SCRIPT_ROWS = 60;

/**
 * AcxProgress 的 11 个字段 —— 这是 72 号文档实测出来的**唯一**产量落点
 * （「AcxProgress.FIELDS 只有 11 个，产量指标必须落进这 11 个名字」）。
 * 页面上逐个列出：现在能不能读到、为什么。
 */
const PROGRESS_FIELDS = [
  { id: "mined", zh: "挖到的矿物" },
  { id: "found", zh: "发现（看到但未必拿到）" },
  { id: "collected", zh: "收进背包的数量" },
  { id: "placed", zh: "放下的方块" },
  { id: "smelted", zh: "熔炼产出" },
  { id: "gathered", zh: "采集（农作物等）" },
  { id: "moved", zh: "搬运的物品" },
  { id: "removed", zh: "移除的方块" },
  { id: "progress", zh: "通用进度计数" },
  { id: "count", zh: "通用计数" },
  { id: "gained", zh: "净增益" },
];

/**
 * 72 号「AC 经营脚本第一批」的八个脚本。
 * `mechanism` 是它的机制假设，`blocker` 是**写文档时已知的阻塞**，
 * `order` 是 72 号文档给的施工顺序。这三栏是骨架的一部分 ——
 * 后来的 AI 照着填「是否已实现」，不用重新推导机制。
 */
const SCRIPT_ROSTER_72 = [
  {
    id: "eat_if_hungry", order: 1, mechanism: "状态机式；饿了→吃，1~2 次调用",
    blocker: "NumenToolCatalog 缺 `eat` 的真实 outputs —— 不补就写等于臆造 schema",
  },
  {
    id: "herd_cattle_sheep", order: 2, mechanism: "天然单次收敛状态机：丢麦 + 走开 + 回头验 + 不行拉黑换一头",
    blocker: "需要 while + AcxTargetBlacklist + 停滞检测三件套；drop_items 只能丢脚下，没有「持物走一段」原语",
  },
  {
    id: "bed_and_bell_routine", order: 3, mechanism: "床搬运 / 入住 / 敲钟三步日常",
    blocker: "「敲钟不保证进指定栏」「丢床不等于放床」—— 需要 live 探针确认，不能固化假设",
  },
  {
    id: "night_sleep_check", order: 4, mechanism: "同 eat 的最简形态：判断+睡",
    blocker: "★ 需常驻，而周期调度器**尚未决策**；拿不到常驻就实现不了",
  },
  {
    id: "farm_batch_wheat", order: 5, mechanism: "一次性播完就完的农田循环",
    blocker: "卡水（无等待原语）+ `planted` 不在 AcxProgress 的 11 个字段里",
  },
  {
    id: "breed_villager_loop", order: 6, mechanism: "结婚 + 加速 + 收回成人数（近似）",
    blocker: "结构上必须多次（5 分钟冷却 + 20 分钟成年），单次收敛做不到",
  },
  {
    id: "farm_supply_check", order: 7, mechanism: "经营总检（组合项：第 1 + 2 + 4 步）",
    blocker: "是组合项，依赖 1/2/4；三个都没通它也通不了",
  },
  {
    id: "night_watch", order: 8, mechanism: "夜间战斗循环",
    blocker: "★ 本批**明确不做**：没有社区方案 + 脚本回执易静默",
  },
];

/**
 * 经济配置（T3-07 / N5）的接线契约。
 * 候选文件名就是探测对象 —— 后来的 AI 只要把配置写到其中任一路径并带上对应键，
 * 这一行会**自动**翻成「已接入」。
 */
const ECONOMY_CONFIG_CANDIDATES = [
  "economy.json", "economy-config.json", "rdd-economy.json",
  "injection.json", "policy.json", "module-toggles.json",
];

const WIRING = [
  {
    id: "progress-fields", owner: "72", name: "产量字段（AcxProgress 11 个）",
    need: "每次运行把 mined/collected/gathered… 11 个字段写进 progress",
    stand: (ctx) => ctx.progressNote,
    verify: "问「这一趟到底收了多少」——现在 progress 恒为空对象，答不出来",
    probe: (ctx) => ctx.progressKeysSeen > 0,
  },
  {
    id: "script-roster-72", owner: "72", name: "72 号八个经营脚本",
    need: "八个脚本进 acx 库并有真实契约",
    stand: (ctx) => ctx.rosterNote,
    verify: "问「她会不会自己种田」——库里一个都没有",
    probe: (ctx) => ctx.rosterFound.length > 0,
  },
  {
    id: "eco-config-file", owner: "N5", name: "经济配置文件",
    need: "一份带版本的配置文件（模块启用 / 经验上限 / 目标禁用）",
    stand: (ctx) => ctx.configNote,
    verify: "问「现在能不能关掉某个模块」——没有配置可读",
    probe: (ctx) => ctx.configFound,
  },
  {
    id: "eco-module-toggle", owner: "N5", name: "模块启用开关",
    need: "关掉某模块后，**依赖它的消费者必须显式知道自己不可用**",
    stand: "没有开关，也就没有「关掉之后依赖怎么表现」这条验收",
    verify: "关掉一个模块，看它的消费者是报错还是继续读旧数据",
    probe: (ctx) => hasKey(ctx, "modules", "moduleEnabled"),
  },
  {
    id: "eco-injection-cap", owner: "N5", name: "经验条目禁用 / 注入数量上限",
    need: "能按条目禁用经验，或限制每次注入的条数与字符数",
    stand: "现在注入量由提示词与服务端常量决定，没有可调的入口",
    verify: "问「这轮最多能注入几条经验」——答不出来",
    probe: (ctx) => hasKey(ctx, "injection", "experienceInjection", "injectLimit"),
  },
  {
    id: "eco-goal-denylist", owner: "N5", name: "目标禁用清单",
    need: "禁掉某些目标，且这是**约束**不是提示文字",
    stand: "没有禁用清单；规划层仍可能把已禁目标写进任务链",
    verify: "禁掉一个目标，看它还能不能被提交",
    probe: (ctx) => hasKey(ctx, "deniedGoals", "goalDenylist", "blockedGoals"),
  },
  {
    id: "eco-config-version", owner: "N5", name: "配置版本与坏配置回退",
    need: "每轮读同一版本；坏配置**保留上一有效版并报错**，不静默用默认值",
    stand: "没有版本字段，也没有「坏配置」这个概念",
    verify: "故意写坏配置，看是报错还是悄悄用默认值",
    probe: (ctx) => ctx.configFound && ctx.configHasVersion,
  },
  {
    id: "eco-hot-switch", owner: "N5", name: "热切换（运行中换配置）",
    need: "改配置后在下一轮生效，不重启游戏",
    stand: "连配置都没有，谈不上热切换",
    verify: "改配置后看下一轮读的是不是新版",
    probe: () => false,
  },
  {
    id: "success-in-records", owner: "73", name: "records.jsonl 记 SUCCESS",
    need: "落盘记录覆盖 SUCCESS，让「成功率」只有一个来源",
    stand: (ctx) => ctx.recordsNote,
    verify: "在 records.jsonl 里找 SUCCESS —— 一条都没有",
    probe: (ctx) => ctx.recordsSuccess > 0,
  },
];

/**
 * 顶层键 → 递归收集所有键（小写），用来探测配置里到底有没有那个字段。
 * ★ 一律**小写**：写判据时写 `configKeys.has('deniedGoals')` 而收集时已经小写化，
 *   这条判据就永远为 false —— 而且症状是「配置明明写了却显示未接入」，
 *   极难查。判据里请一律用 `hasKey()`。
 */
function collectKeys(o, into) {
  if (!o || typeof o !== "object") return into;
  if (Array.isArray(o)) { for (const x of o) collectKeys(x, into); return into; }
  for (const k of Object.keys(o)) {
    into.add(k.toLowerCase());
    collectKeys(o[k], into);
  }
  return into;
}

/** 大小写无关地查配置键。 */
function hasKey(ctx, ...names) {
  const keys = ctx.configKeys || new Set();
  for (const n of names) if (keys.has(String(n).toLowerCase())) return true;
  return false;
}

function isoOrNull(v) {
  return v == null ? null : new Date(v).toISOString();
}
function num(v) {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}
function readJsonSafe(raw) {
  try { const o = JSON.parse(raw); return o && typeof o === "object" ? o : null; }
  catch { return null; }
}

export function createEconomyView(MONITOR_DIR) {
  const CONFIG_DIR = resolve(MONITOR_DIR, "..");
  const ACX_DIR = join(CONFIG_DIR, "acx");
  let cache = null;

  return {
    async all() {
      const nowMs = Date.now();
      if (cache && nowMs - cache.at < CACHE_MS) return cache.data;

      // ── AC 脚本库（library.json + 磁盘上的 .ac）──────────────
      const sources = {};
      let libraryDefs = {};
      let libraryReadable = false, libraryError = null;
      try {
        const raw = await readFile(join(ACX_DIR, "library.json"), "utf8");
        const o = readJsonSafe(raw);
        if (o && o.defs && typeof o.defs === "object") { libraryDefs = o.defs; libraryReadable = true; }
        else libraryError = "library.json 里没有 defs 对象";
      } catch (e) { libraryError = String(e.message); }
      sources.library = {
        key: "library", label: "AC 脚本库", file: "config/numen/acx/library.json",
        readable: libraryReadable, error: libraryError,
        defs: Object.keys(libraryDefs).length,
      };

      // 磁盘上的脚本文件：lib/stable/*.ac 是「会被自动批准」的那一批，
      // `.stale-*` 是被替换下来的旧版（它们的存在说明脚本改过）。
      let stableFiles = [], staleFiles = 0, stableReadable = false;
      try {
        const names = await readdir(join(ACX_DIR, "lib", "stable"));
        stableReadable = true;
        for (const n of names) {
          if (/\.stale-\d+$/.test(n)) { staleFiles += 1; continue; }
          if (n.endsWith(".ac")) stableFiles.push(n.replace(/\.ac$/, ""));
        }
      } catch { /* 读不到就是没有，后面 coverage 会标 */ }
      sources.stable = {
        key: "stable", label: "磁盘脚本（lib/stable）", file: "config/numen/acx/lib/stable/*.ac",
        readable: stableReadable, files: stableFiles.length, staleFiles,
      };

      // ── 落盘运行记录（records.jsonl）────────────────────────
      // ★ 只落 PAUSED / FAIL / TIMEOUT。**SUCCESS 不落盘**（见文件头纪律〔1〕）。
      const records = [];
      let recordsWin = null, recordsReadable = false;
      try {
        recordsWin = await readJsonlWindow(join(ACX_DIR, "records.jsonl"), RECORDS_MAX_BYTES, RECORDS_MAX_EVENTS);
        recordsReadable = true;
        for (const e of recordsWin.events) if (e && typeof e === "object" && e.run_id) records.push(e);
      } catch { recordsWin = null; }

      const progressKeysSeen = new Set();
      let recordsSuccess = 0;
      const recByName = {};
      for (const r of records) {
        const name = r.ac_name || "?";
        if (!recByName[name]) {
          recByName[name] = {
            name, records: 0, paused: 0, failed: 0, timeout: 0, success: 0,
            elapsedSumMs: 0, elapsedMaxMs: 0, loopSum: 0, stagnant: 0,
            lastAt: null, lastStatus: null, lastError: null, progressKeys: new Set(),
          };
        }
        const g = recByName[name];
        g.records += 1;
        const st = r.status || "?";
        if (st === "SUCCESS") { g.success += 1; recordsSuccess += 1; }
        else if (st === "PAUSED") g.paused += 1;
        else if (st === "TIMEOUT") g.timeout += 1;
        else if (st === "FAIL") g.failed += 1;
        const el = num(r.elapsed_ms) || 0;
        g.elapsedSumMs += el;
        if (el > g.elapsedMaxMs) g.elapsedMaxMs = el;
        g.loopSum += num(r.loop_count) || 0;
        if (r.stagnant_step) g.stagnant += 1;
        const ts = num(r.timestamp);
        if (ts != null && (g.lastAt == null || ts > g.lastAt)) { g.lastAt = ts; g.lastStatus = st; g.lastError = r.error_message || r.paused_reason || null; }
        // ★ progress 实测恒为 {}；这里真的去看它有没有键
        if (r.progress && typeof r.progress === "object") {
          for (const k of Object.keys(r.progress)) { progressKeysSeen.add(k); g.progressKeys.add(k); }
        }
      }

      // ── 运行事件流（monitor/acx.jsonl）—— 成功率只能从这里算 ──
      const evNames = {};
      let acxWin = null, acxReadable = false;
      try {
        acxWin = await readJsonlWindow(join(MONITOR_DIR, "acx.jsonl"), ACX_EVENT_MAX_BYTES, ACX_EVENT_MAX_EVENTS);
        acxReadable = true;
      } catch { acxWin = null; }
      for (const e of (acxWin ? acxWin.events : [])) {
        if (!e || e.type !== "acx_event") continue;
        const d = e.data || e.payload || {};
        const name = d.ac_name || "?";
        if (!evNames[name]) {
          evNames[name] = {
            name, runStarted: 0, runSuccess: 0, runFail: 0, stepStarted: 0,
            stepSucceeded: 0, stepFailed: 0, elapsedSumMs: 0, loopSum: 0,
            stagnantHits: 0, lastAt: null, lastStatus: null,
          };
        }
        const g = evNames[name];
        const ts = Date.parse(e.timestamp);
        const kind = d.kind || "";
        if (kind === "RUN_STARTED") g.runStarted += 1;
        else if (kind === "RUN_FINISHED") {
          if (String(d.status || "") === "SUCCESS") g.runSuccess += 1; else g.runFail += 1;
          if (num(d.elapsed_ms) != null) g.elapsedSumMs += d.elapsed_ms;
          g.lastStatus = String(d.status || "");
        } else if (kind === "STEP_STARTED") g.stepStarted += 1;
        else if (kind === "STEP_SUCCEEDED") g.stepSucceeded += 1;
        else if (kind === "STEP_FAILED") g.stepFailed += 1;
        if (Number.isFinite(ts) && (g.lastAt == null || ts > g.lastAt)) g.lastAt = ts;
        if (d.loop_count != null) g.loopSum += num(d.loop_count) || 0;
        if (d.stagnant_rounds != null && num(d.stagnant_rounds) > 0) g.stagnantHits += 1;
      }

      // ── 脚本台账：库里的定义 ∪ 磁盘上的文件 ∪ 两个日志里出现过的名字 ──
      const allNames = new Set();
      for (const k of Object.keys(libraryDefs)) allNames.add(k);
      for (const k of stableFiles) allNames.add(k);
      for (const k of Object.keys(recByName)) allNames.add(k);
      for (const k of Object.keys(evNames)) allNames.add(k);
      const scripts = [...allNames].map((name) => {
        const def = libraryDefs[name] || null;
        const rec = recByName[name] || null;
        const ev = evNames[name] || null;
        return {
          name,
          inLibrary: !!def,
          defKeys: def ? Object.keys(def).slice(0, 12) : [],
          onDiskStable: stableFiles.indexOf(name) >= 0,
          // ★ 这个字段名很要紧：`def` 里的东西**不等于**它是可用脚本
          //   —— 72 号文档明确「未实机验收的脚本不得放会自动批准的 stable 路径」。
          defNote: def && (def.note || def.description || def.status || null) || null,
          records: rec ? rec.records : 0,
          recordsPaused: rec ? rec.paused : 0,
          recordsFailed: rec ? rec.failed : 0,
          recordsTimeout: rec ? rec.timeout : 0,
          recordsSuccess: rec ? rec.success : 0,
          recordsAvgElapsedMs: rec && rec.records ? Math.round(rec.elapsedSumMs / rec.records) : null,
          recordsStagnantRuns: rec ? rec.stagnant : 0,
          runStarted: ev ? ev.runStarted : 0,
          runSuccess: ev ? ev.runSuccess : 0,
          runFail: ev ? ev.runFail : 0,
          stepStarted: ev ? ev.stepStarted : 0,
          stepFailed: ev ? ev.stepFailed : 0,
          eventAvgElapsedMs: ev && ev.runStarted ? Math.round(ev.elapsedSumMs / ev.runStarted) : null,
          lastAt: isoOrNull(ev ? ev.lastAt : (rec ? rec.lastAt : null)),
          lastStatus: ev ? ev.lastStatus : (rec ? rec.lastStatus : null),
          lastError: rec ? rec.lastError : null,
          // ★ 成功率只从事件流算；分母为 0 ⇒ null，不写 0%
          successRate: ev && ev.runStarted > 0
            ? Math.round((ev.runSuccess / ev.runStarted) * 1000) / 10 : null,
          progressKeys: rec ? [...rec.progressKeys] : [],
        };
      });
      scripts.sort((a, b) => (b.runStarted + b.records) - (a.runStarted + a.records));

      // ── 72 号八个脚本的 roster（是否出现由运行时探测决定）──────
      const rosterFound = SCRIPT_ROSTER_72.filter((s) => allNames.has(s.id));
      const roster = SCRIPT_ROSTER_72.map((s) => {
        const hit = scripts.find((x) => x.name === s.id);
        return Object.assign({}, s, {
          present: !!hit,
          inLibrary: hit ? hit.inLibrary : false,
          onDiskStable: hit ? hit.onDiskStable : false,
          runStarted: hit ? hit.runStarted : 0,
          // ★ 没找到就是「未实现」，不是「运行了 0 次」
          state: !hit ? "未实现（库里没有）"
            : (hit.runStarted > 0 ? "跑过 " + hit.runStarted + " 次" : "在库但没跑过"),
        });
      });

      // ── 经济配置：候选文件名全探测一遍 ─────────────────────
      let configFound = false, configError = null, configPath = null, configHasVersion = false;
      const configKeys = new Set();
      const tried = [];
      for (const c of ECONOMY_CONFIG_CANDIDATES) {
        const p = join(CONFIG_DIR, c);
        tried.push(c);
        let raw = null;
        try { raw = await readFile(p, "utf8"); } catch { continue; }
        const o = readJsonSafe(raw);
        if (!o) { configError = c + " 存在但不是合法 JSON"; continue; }
        configFound = true;
        configPath = c;
        configHasVersion = collectKeys(o, configKeys).has("version");
        break;
      }
      const configNote = configFound
        ? "读到 " + configPath + "（键：" + ([...configKeys].slice(0, 8).join(",") || "空") + "）"
        : "在 " + CONFIG_DIR + " 下逐个试过 " + tried.join(" / ") + "，**一个都没有** ⇒ 经济配置还没落地。"
          + "本页不显示任何默认值 —— 默认值是我编的，不是系统里的。";

      const recordsNote = recordsReadable
        ? "records.jsonl 实读 " + records.length + " 条，其中 SUCCESS **" + recordsSuccess + " 条**"
          + (recordsSuccess === 0 ? " —— 这个日志根本不记成功，成功率必须从 monitor/acx.jsonl 算。" : "。")
        : "读不到 records.jsonl。";
      const progressNote = "records.jsonl 里 progress 字段实测出现过的键："
        + (progressKeysSeen.size ? [...progressKeysSeen].join("、") : "**一个都没有**（恒为 {}）")
        + " ⇒ AcxProgress 的 11 个字段一个都没有真实读数。";
      const rosterNote = "acx 库里逐个查过这八个名字，命中 " + rosterFound.length + " 个"
        + (rosterFound.length ? "：" + rosterFound.map((x) => x.id).join("、") : " ⇒ 八个都还没实现。");

      const probeCtx = {
        progressKeysSeen: progressKeysSeen.size,
        recordsSuccess, recordsNote, progressNote, rosterNote,
        rosterFound, configFound, configKeys, configHasVersion, configNote,
      };
      const wiring = WIRING.map((w) => {
        let connected = false, probeError = null, stand = null;
        try { connected = !!w.probe(probeCtx); } catch (e) { probeError = String(e.message); }
        try { stand = typeof w.stand === "function" ? w.stand(probeCtx) : w.stand; }
        catch (e) { stand = "（描述取数失败：" + String(e.message) + "）"; }
        return {
          id: w.id, owner: w.owner, name: w.name, need: w.need, stand, verify: w.verify,
          state: connected ? "connected" : "missing", connected, probeError,
        };
      });

      // 覆盖率 / 新鲜度
      sources.records = {
        key: "records", label: "落盘运行记录", file: "config/numen/acx/records.jsonl",
        readable: recordsReadable, rows: records.length,
        truncated: recordsWin ? recordsWin.truncated : null,
        verdict: recordsWin ? null : "unreadable",
      };
      sources.events = {
        key: "events", label: "运行事件流", file: "monitor/acx.jsonl",
        readable: acxReadable, truncated: acxWin ? acxWin.truncated : null,
      };
      const coverage = {
        sources: [sources.library, sources.stable, sources.records, sources.events],
        scriptsTotal: scripts.length,
        scriptsListed: Math.min(scripts.length, MAX_SCRIPT_ROWS),
        scriptsDropped: Math.max(0, scripts.length - MAX_SCRIPT_ROWS),
        candidatesTried: tried,
        note_zh: "四个来源分属两层：library/lib-stable 是「有哪些脚本」，records/acx.jsonl 是「跑了什么」。"
          + "**records 不记 SUCCESS**，所以成功率只能从 acx.jsonl 算，两边不能相加。",
      };
      const anyReadable = libraryReadable || stableReadable || recordsReadable || acxReadable;
      const freshness = Object.assign(
        { readable: anyReadable, per_source: coverage.sources.map((s) => ({ key: s.key, readable: s.readable })) },
        (await freshnessOf({ monitorDir: MONITOR_DIR, fileName: "acx.jsonl", window: acxWin, nowMs }).catch(() => null)) || {}
      );
      if (!anyReadable) {
        freshness.verdict = "unreadable";
        freshness.verdict_zh = "★ AC 侧一个来源都读不到 —— 下面的数字全部无效";
      } else if (!acxReadable) {
        freshness.verdict = "old_while_game_alive";
        freshness.verdict_zh = "★ 运行事件流读不到 —— 成功率那一块没有依据，显示「未接入」";
      }

      const progressFields = PROGRESS_FIELDS.map((f) => ({
        id: f.id, zh: f.zh,
        seen: progressKeysSeen.has(f.id),
        // ★ 证据摆在页面上：不是「没找到」，是「progress 恒为 {}」
        evidence: progressKeysSeen.size
          ? "progress 里出现过：" + [...progressKeysSeen].join("、")
          : "records.jsonl 的 progress 恒为 {} —— 这个字段没有任何读数",
      }));

      const emptyReasons = [];
      if (!scripts.length) emptyReasons.push("既没读到 acx 脚本库，也没读到磁盘脚本 —— 下面没有脚本可列。");
      if (!acxReadable) emptyReasons.push("读不到 monitor/acx.jsonl ⇒ 成功率整块「未接入」（不是 0%）。");

      const data = {
        ok: true,
        generatedAt: isoOrNull(nowMs),
        monitorDir: MONITOR_DIR,
        configDir: CONFIG_DIR,
        progressFields,
        scripts: scripts.slice(0, MAX_SCRIPT_ROWS),
        roster,
        rosterFound: rosterFound.length,
        wiring,
        wiringSummary: {
          connected: wiring.filter((w) => w.connected).length,
          total: wiring.length,
          note_zh: "与「单一世界」页同一套做法：每一行都是**运行时探测**出来的。"
            + "后来的 AI 把数据接上，这一行会自动翻成「已接入」，不用改渲染代码。",
        },
        totals: {
          scripts: scripts.length,
          onDiskStable: stableFiles.length,
          staleBackups: staleFiles,
          recordsRead: records.length,
          recordsSuccess,
          runStarted: scripts.reduce((a, s) => a + s.runStarted, 0),
          runSuccess: scripts.reduce((a, s) => a + s.runSuccess, 0),
          runFail: scripts.reduce((a, s) => a + s.runFail, 0),
          stepFailed: scripts.reduce((a, s) => a + s.stepFailed, 0),
        },
        coverage,
        freshness,
        caveats: [
          { id: "records-no-success", note_zh: "records.jsonl **只落 PAUSED / FAIL / TIMEOUT**，SUCCESS 不落盘 ⇒ 「这里没有成功记录」≠「没有成功运行」。成功率只从 monitor/acx.jsonl 的 RUN_FINISHED 算。" },
          { id: "two-logs-not-addable", note_zh: "records.jsonl 与 monitor/acx.jsonl 是同一批运行的两个视图，**不可相加**。" },
          { id: "progress-always-empty", note_zh: "AcxProgress 的 11 个产量字段一个都没有真实读数（progress 恒为 {}）。本页不给产量曲线，也不画空曲线。" },
          { id: "def-is-not-approved", note_zh: "脚本在 library.json 里有定义 ≠ 它是可自动批准的 stable 脚本。72 号明确：未实机验收的不得放 stable 路径。" },
          { id: "no-invented-defaults", note_zh: "经济配置一个文件都没读到 ⇒ 本页**不显示任何默认值**。编一个「模块默认全开」出来就是在骗人。" },
          { id: "not-a-money-ledger", note_zh: "这一页不是钱。绿宝石/经验/时间的成本口径在 ①.6 Token 消耗与经验注入里，两边不可相加。" },
        ],
        emptyReasons,
      };
      cache = { at: nowMs, data };
      return data;
    },
  };
}