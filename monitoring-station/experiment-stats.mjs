// ============================================================
// experiment-stats.mjs —— 实验统计：每个 AI × 每个目标 的行为统计
// ============================================================
//
// 〔为什么单独一页（owner 2026-10-05）〕
//   已有页面各自都能看，但都缺一个视角：**把同一只 AI 在同一批目标下的
//   死亡 / AC 调用 / 行动次数并排放在一起**。现在的实际情况是：
//     · 死亡只在「② 工具 2.3 埋点」里按事件列出来，没有任何汇总
//     · AC 只在「④ ACX」里按脚本看，而 AC 事件**根本没有同伴字段**
//     · 行动次数（tool_call）只在事件流里，没有按目标分组的计数
//     · 目标只有「③ 任务链 3.1」那一条当前链，历史目标看不到
//   于是「她在这个目标下死了几次 / 调了多少次 AC / 干了多少件事」这个问题，
//   结构上答不出来 —— 这就是本页要补的。
//
// ────────────────────────────────────────────────────────────
// ★★★ 本页的七条口径纪律（每一条都在下面代码里就地标注）
//
// 〔1〕**AC 调用次数按同伴/目标的归属是「推断」，不是事实。**
//   实测 `acx.jsonl` 的 `acx_event` 与 `ac.jsonl` 的 `ac_event` 都**没有**
//   `companionId` / `companion_id`（键集：kind/run_id/ac_name/step_id/block/
//   status/elapsed_ms/…）。本页的归属靠**时间线推断**，分两档：
//     · 某一时刻**只有一只**同伴的目标段开着 ⇒ 归给它（`inferred_single`，强）
//     · 多只同时开着 ⇒ 看**那一刻之前谁刚活动过**：领先超过 AC_LEAD_MS 才归
//       给它（`inferred_leader`，弱，页面必须标出来）；否则**不猜**，
//       计入 `ambiguous`
//     · 一只都没开 ⇒ 计入 `unattributed`
//   ★ 为什么要有第二档（第一版只有「唯一打开」这一档，实测立刻废掉）：
//     一个同伴只要开过目标，那一段就会**一直开着**（没有「目标结束」这个事件）。
//     于是只要有两只 AI 被监督过，「唯一打开」就几乎永远不成立 —— AC 那几列会
//     全是空的，等于这一页没做。所以补一个「最近活动领先者」的弱推断档。
//     它仍然是推断，页面上标 `推断` 并把两档分开报数。
//
// 〔2〕**死亡事件里 `data.task` 实测是空串**，所以死亡也没有自带目标。
//   本页按「死亡时刻该同伴当前的目标段」归属，同属推断。
//
// 〔3〕**两个 AC 日志不许相加。** `acx.jsonl`（状态机视图）与 `ac.jsonl`
//   （旧执行层视图）是同一件事的两个日志。本页只用 acx 出 AC 指标；
//   ac 的条数只作为 `ac.journalEvents` 单独列出并标注「不可相加」。
//
// 〔4〕**窗口截断必须报。** 每次只读各文件尾部，较大的文件（tools.jsonl 11MB、
//   rdd.jsonl 11MB、events.jsonl 7.6MB）一定会被截断，`coverage` 里逐文件报出
//   「文件共多少字节 / 本次读了多少 / 丢了多少」。
//
// 〔5〕**读不到 ≠ 0。** 任一来源读不到 ⇒ 该来源的指标带 `unreadable` 标记，
//   页面据此显示「未接入」而不是 0。
//
// 〔6〕**派生比率的分母为 0 时给 null，不给 0。**
//   例：`deathsPer100Actions` 在 actions=0 时是 null ——
//   「没行动过所以死亡率 0」和「不知道」是两件事。
//
// 〔7〕**时间一律 UTC**；曲线的横轴必须连续（补空桶），但**必须同时报 gaps** ——
//   被标成 gap 的桶是「那一段根本没读到」，与「读到但一条记录都没有」含义不同。
// ============================================================

import { readdir, stat } from "node:fs/promises";
import { join } from "node:path";
import { readJsonlWindow } from "./tail-jsonl.mjs";
import { freshnessOf } from "./freshness.mjs";

const MB = 1024 * 1024;

/** 服务端缓存：这一页要读 ~25MB，页面 12 秒轮一次，不缓存纯属烧磁盘。 */
const CACHE_MS = 12000;

/** 每个来源的字节预算 / 事件上限 / 轮转策略。 */
const SOURCES = [
  { key: "rdd", file: "rdd.jsonl", bytes: 10 * MB, maxEvents: 20000, rotates: true, label: "RDD 任务链 / 目标" },
  { key: "instrumentation", file: "instrumentation.jsonl", bytes: 3 * MB, maxEvents: 8000, rotates: false, label: "埋点（死亡 / 空转 / 资产不匹配）" },
  { key: "tools", file: "tools.jsonl", bytes: 5 * MB, maxEvents: 20000, rotates: false, label: "工具调用与结果" },
  { key: "events", file: "events.jsonl", bytes: 3 * MB, maxEvents: 12000, rotates: false, label: "任务结果" },
  { key: "ai", file: "ai.jsonl", bytes: 2 * MB, maxEvents: 8000, rotates: false, label: "AI 回合" },
  { key: "acx", file: "acx.jsonl", bytes: 2 * MB, maxEvents: 20000, rotates: false, label: "AC 状态机" },
  { key: "ac", file: "ac.jsonl", bytes: 1 * MB, maxEvents: 4000, rotates: false, label: "AC 执行层（旧日志）" },
  { key: "expmem", file: "expmem.jsonl", bytes: 1 * MB, maxEvents: 4000, rotates: false, label: "经验记忆" },
  { key: "learner", file: "learner.jsonl", bytes: 1 * MB, maxEvents: 4000, rotates: false, label: "Learner 复盘" },
];

/**
 * 指标字典。`kind` 决定页面颜色：action 行动 / good 成果 / bad 受损 / warn 异常。
 * `source` 说明这个数的出处，供页面标注：
 *   event    事件自带同伴字段 ⇒ 归属是事实
 *   timeline 事件没有同伴字段 ⇒ 归属是时间线推断（见文件头〔1〕）
 *   derived  由上面两个算出来的比率
 */
const METRICS = [
  { id: "deaths", label: "死亡", unit: "次", kind: "bad", source: "event", hint: "instrumentation.death" },
  { id: "actions", label: "行动（工具调用）", unit: "次", kind: "action", source: "event", hint: "tools.tool_call" },
  { id: "acRuns", label: "AC 调用", unit: "次", kind: "action", source: "timeline", hint: "acx RUN_STARTED（归属为推断）" },
  { id: "acSteps", label: "AC 步骤", unit: "步", kind: "action", source: "timeline", hint: "acx STEP_STARTED（归属为推断）" },
  { id: "acStepFails", label: "AC 步骤失败", unit: "步", kind: "bad", source: "timeline", hint: "acx STEP_FAILED（归属为推断）" },
  { id: "turns", label: "AI 回合", unit: "轮", kind: "action", source: "event", hint: "ai.turn" },
  { id: "loops", label: "空转", unit: "次", kind: "bad", source: "event", hint: "instrumentation.loop_detected" },
  { id: "tasksDone", label: "任务完成", unit: "次", kind: "good", source: "event", hint: "events.task_finished status=done" },
  { id: "recalls", label: "经验召回", unit: "次", kind: "good", source: "event", hint: "expmem.recalled" },
  { id: "reviews", label: "学习者复盘", unit: "次", kind: "good", source: "event", hint: "learner.reviewed" },
  { id: "rejected", label: "工具被拒", unit: "次", kind: "warn", source: "event", hint: "tools.tool_rejected" },
  { id: "assetMismatch", label: "资产不匹配", unit: "次", kind: "warn", source: "event", hint: "instrumentation.asset_mismatch" },
  { id: "memosLearned", label: "经验条目新增", unit: "条", kind: "good", source: "timeline", hint: "expmem.learned（无同伴字段 ⇒ 推断）" },
  { id: "stalls", label: "支线卡住", unit: "次", kind: "bad", source: "event", hint: "rdd.subtask_stalled" },
  { id: "repeatDeaths", label: "重复死亡", unit: "次", kind: "bad", source: "event", hint: "rdd.repeat_death_nearby" },
  { id: "deathsPer100Actions", label: "死亡率", unit: "次/百次行动", kind: "bad", source: "derived", hint: "死亡 ÷ 行动 ×100（行动为 0 时不显示）" },
  { id: "stepFailRate", label: "AC 步骤失败率", unit: "%", kind: "bad", source: "derived", hint: "失败步骤 ÷ 全部步骤 ×100（步骤为 0 时不显示）" },
  { id: "actionsPerTurn", label: "每回合行动数", unit: "次/轮", kind: "action", source: "derived", hint: "行动 ÷ 回合（回合为 0 时不显示）" },
];

const RAW_METRIC_IDS = METRICS.filter((m) => m.source !== "derived").map((m) => m.id);

/** 空计数对象（只含可累加的原始指标）。 */
function emptyCounts() {
  const o = {};
  for (const id of RAW_METRIC_IDS) o[id] = 0;
  return o;
}

/** 派生比率：分母为 0 ⇒ null（文件头〔6〕）。 */
function derived(counts) {
  return {
    deathsPer100Actions: counts.actions > 0 ? Math.round((counts.deaths / counts.actions) * 1000) / 10 : null,
    stepFailRate: counts.acSteps > 0 ? Math.round((counts.acStepFails / counts.acSteps) * 1000) / 10 : null,
    actionsPerTurn: counts.turns > 0 ? Math.round((counts.actions / counts.turns) * 10) / 10 : null,
  };
}

function isoOrNull(t) {
  return Number.isFinite(t) ? new Date(t).toISOString() : null;
}

function parseTs(s) {
  const t = Date.parse(s);
  return Number.isFinite(t) ? t : null;
}

function eventTs(e) {
  return e && e.timestamp ? parseTs(e.timestamp) : null;
}

/** 事件体：优先 data，其次 payload（/api/snapshot 会补 payload 别名）。 */
function body(e) {
  return (e && (e.data || e.payload)) || {};
}

/** ★ 同伴字段有两种拼法（companionId / companion_id），漏一种就少一半数据。 */
function companionOf(e) {
  const d = body(e);
  return (d.companionId || d.companion_id || d.companion || "") || null;
}

function num(v) {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

// ────────────────────────────────────────────────────────────
// 目标（goal）是怎么定的 —— 「每一个目标」的定义必须写在页面上
// ────────────────────────────────────────────────────────────
//
//  优先级 1：rdd.jsonl 的 `taskchain_snapshot`，取 `data.taskChain`（JSON 字符串）里的
//            `goalId` 作身份、`description` 作标题。**同一时刻的快照共享一个
//            goalId ⇒ 目标从开始到结束形成连续的「目标段」**，每段天然带着
//            「什么时候开始 / 什么时候结束 / 中间换过几次」。
//  优先级 2：某同伴若一条 taskchain_snapshot 都没有 ⇒ 退化到 `goal_staged`
//            的 `objective`（实测该事件带 objective + stages，但只出现过 1 次，
//            所以只能当兜底，不能当主源）。
//  优先级 3：两者都没有 ⇒ goalKey = `__unknown__`，标题「（目标未记录）」。
//            **页面必须显示成「未接入」而不是一个正常目标** ——
//            否则「这只 AI 有一个目标、死了 3 次」会被读成「她在某个目标下死了 3 次」。
//
// ★ 只用 goalId 切段（不切小步）：实测同一次游戏里 `currentSubtaskId` 每几秒变一次，
//   按它切会把一个目标切成上百段，页面的「目标」列会变成一堆噪音。
const UNKNOWN_GOAL = "__unknown__";
const UNKNOWN_GOAL_LABEL = "（目标未记录）";

function parseChain(raw) {
  if (raw && typeof raw === "object") return raw;
  if (typeof raw !== "string" || !raw) return null;
  try {
    const o = JSON.parse(raw);
    return o && typeof o === "object" ? o : null;
  } catch { return null; }
}

/** 目标身份：goalId 优先；没有就用 description 归一化当 key。 */
function goalIdentity(goalId, description) {
  const gid = goalId == null ? "" : String(goalId).trim();
  if (gid) return { key: gid, source: "goalId" };
  const desc = description == null ? "" : String(description).trim();
  if (desc) return { key: "desc:" + desc.replace(/\s+/g, " ").slice(0, 80), source: "description" };
  return { key: UNKNOWN_GOAL, source: "unknown" };
}

/**
 * 从 rdd 事件里重建「同伴 × 目标段」时间轴。
 */
function buildGoalTimeline(rddEvents) {
  const sorted = rddEvents
    .filter((e) => eventTs(e) != null)
    .sort((a, b) => eventTs(a) - eventTs(b));

  const companions = new Map();   // cid → { segments, lastGoalKey, sawSnapshot, lastAt }
  const goals = new Map();        // goalKey → { key, label, source, snapshots, firstAt, lastAt }

  for (const e of sorted) {
    const d = body(e);
    const ts = eventTs(e);
    if (e.type === "taskchain_snapshot") {
      const cid = companionOf(e);
      const chain = parseChain(d.taskChain);
      const id = goalIdentity(chain && chain.goalId, chain && chain.description);
      upsertGoal(goals, id.key, (chain && chain.description) || id.key, id.source, ts);
      if (cid) {
        const c = touchCompanion(companions, cid, ts, true);
        cutSegment(c, id, ts);
      }
    } else if (e.type === "goal_staged") {
      const cid = companionOf(e);
      if (!cid) continue;
      const id = goalIdentity(null, d.objective);
      upsertGoal(goals, id.key, d.objective || id.key, "objective", ts);
      const c = touchCompanion(companions, cid, ts, false);
      if (c.sawSnapshot) continue;      // 有快照就以快照为准（优先级 1）
      cutSegment(c, id, ts);
    }
  }

  for (const c of companions.values()) {
    c.segments.sort((a, b) => a.startedAt - b.startedAt);
    for (let i = 0; i < c.segments.length; i++) {
      const end = i + 1 < c.segments.length ? c.segments[i + 1].startedAt : Infinity;
      c.segments[i].endedAt = end;
      c.segments[i].lastAt = Number.isFinite(end) ? end : c.lastAt;
    }
  }
  return { companions, goals };
}

function touchCompanion(companions, cid, ts, sawSnapshot) {
  let c = companions.get(cid);
  if (!c) { c = { id: cid, segments: [], lastGoalKey: null, sawSnapshot: false, lastAt: ts }; companions.set(cid, c); }
  if (ts > c.lastAt) c.lastAt = ts;
  if (sawSnapshot) c.sawSnapshot = true;
  return c;
}

function cutSegment(c, id, ts) {
  if (c.lastGoalKey === id.key) return;
  c.lastGoalKey = id.key;
  c.segments.push({ companionId: c.id, goalKey: id.key, source: id.source, startedAt: ts, endedAt: Infinity, lastAt: ts });
}

function upsertGoal(goals, key, label, source, ts) {
  let g = goals.get(key);
  if (!g) {
    g = { key, label: label || key, source, snapshots: 0, firstAt: ts, lastAt: ts };
    goals.set(key, g);
  } else {
    // 描述可能后来才带上（首条快照只有 id）⇒ 有更好的标题就换，不丢 id
    if ((g.label === g.key || !g.label) && label && label !== key) g.label = label;
    if (ts < g.firstAt) g.firstAt = ts;
    if (ts > g.lastAt) g.lastAt = ts;
  }
  g.snapshots += 1;
  return g;
}

/** 某一时刻该同伴正在跑哪个目标段（最后一条 startedAt <= ts 的段）。 */
function goalAt(companion, ts) {
  const segs = companion.segments;
  if (!segs.length) return null;
  let lo = 0, hi = segs.length - 1, hit = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (segs[mid].startedAt <= ts) { hit = mid; lo = mid + 1; } else { hi = mid - 1; }
  }
  return hit >= 0 ? segs[hit] : null;
}

/** ★ 该时刻全局有几只同伴的目标段开着 —— 决定 AC 能否唯一归属（文件头〔1〕）。 */
function openSegmentsAt(allSegments, ts) {
  const open = [];
  for (const seg of allSegments) if (seg.startedAt <= ts && ts < seg.endedAt) open.push(seg);
  return open;
}

// ────────────────────────────────────────────────────────────
// 序列分桶
// ────────────────────────────────────────────────────────────
//
// ★ 为什么这里**补空桶**、而 token 页刻意**不补**：
//   token 页画「每分钟有没有在烧」，补空分钟会让人以为那几分钟在烧 ⇒ 不补。
//   本页画**曲线**，横轴连续才有意义；不补就会出现假的「陡升陡降」。
//   ⇒ 补，但**必须同时报 gaps**：其中几个桶一条记录都没有 ——
//   那是真的没在跑，页面不许把它画成 0 值。
const BUCKETS = [
  { ms: 60e3, label: "1 分钟" },
  { ms: 300e3, label: "5 分钟" },
  { ms: 900e3, label: "15 分钟" },
  { ms: 1800e3, label: "30 分钟" },
  { ms: 3600e3, label: "1 小时" },
  { ms: 10800e3, label: "3 小时" },
  { ms: 21600e3, label: "6 小时" },
  { ms: 43200e3, label: "12 小时" },
  { ms: 86400e3, label: "1 天" },
];
const MAX_BUCKETS = 48;

/**
 * 弱推断的领先阈值：多只同伴的目标段同时开着时，最近活动领先**超过**这么多毫秒
 * 才认它。用严格大于是因为「刚好领先 60 秒」和「同时发生」在观测上分不开。
 */
const AC_LEAD_MS = 60000;

function chooseBucket(spanMs) {
  for (const b of BUCKETS) if (spanMs / b.ms <= MAX_BUCKETS) return b;
  return BUCKETS[BUCKETS.length - 1];
}

function bucketStart(ts, ms) {
  return Math.floor(ts / ms) * ms;
}

// ────────────────────────────────────────────────────────────

export function createExperimentStatsView(MONITOR_DIR) {
  let cache = null;

  /**
   * 读一个来源（可能含轮转文件）。
   *
   * ★ 轮转文件怎么处理：rdd.jsonl 实测会被轮转成 `rdd-<millis>.jsonl`（单个 16MB），
   *   历史目标只存在于轮转文件里，所以 rdd 允许多读；
   *   其余文件实测**没有**轮转（tools/events/ai/acx 只有一个活跃文件），按单文件读。
   *   被跳过的文件必须报出来（`filesSkipped`），否则「覆盖时段」会显得比实际宽。
   */
  async function readSource(spec, nowMs) {
    let names = [];
    try { names = await readdir(MONITOR_DIR); }
    catch {
      return { readable: false, missing: true, events: [], filesScanned: [], filesSkipped: [], window: null, freshness: null };
    }
    const base = spec.file.replace(/[.]jsonl$/, "");
    const re = spec.rotates ? new RegExp("^" + base + "(-.*)?\\.jsonl$") : new RegExp("^" + spec.file.replace(/[.]/g, "\\.") + "$");
    const picked = names.filter((n) => re.test(n));
    const withStat = [];
    for (const n of picked) {
      try {
        const s = await stat(join(MONITOR_DIR, n));
        withStat.push({ name: n, mtimeMs: s.mtimeMs, sizeBytes: s.sizeBytes });
      } catch { /* stat 读不到 = 文件不存在，不当 0 */ }
    }
    // 活跃文件排最前：它才是「当前这一局」
    withStat.sort((a, b) => (b.name === spec.file ? 1 : 0) - (a.name === spec.file ? 1 : 0));
    withStat.sort((a, b) => b.mtimeMs - a.mtimeMs);

    const events = [];
    const filesScanned = [];
    let budget = spec.bytes;
    let skipped = [];
    for (let i = 0; i < withStat.length; i++) {
      const f = withStat[i];
      if (f.sizeBytes > budget) { skipped = withStat.slice(i).map((x) => x.name); break; }
      let win = null;
      try {
        win = await readJsonlWindow(join(MONITOR_DIR, f.name), Math.min(budget, spec.maxEvents * 400), spec.maxEvents);
      } catch { skipped = withStat.slice(i).map((x) => x.name); break; }
      budget -= f.sizeBytes;
      filesScanned.push({
        file: f.name, sizeBytes: f.sizeBytes, eventsRead: win.events.length,
        truncated: win.truncated, bytesDropped: win.bytesDropped, linesDropped: win.linesDropped,
        mtime: isoOrNull(f.mtimeMs),
      });
      for (const e of win.events) events.push(e);
      if (budget <= 0) { skipped = withStat.slice(i + 1).map((x) => x.name); break; }
    }

    const primaryFile = filesScanned.length ? filesScanned[0].file : spec.file;
    let window = null;
    try { window = await readJsonlWindow(join(MONITOR_DIR, primaryFile), 1 << 20, 50); }
    catch { window = null; }
    const freshness = await freshnessOf({ monitorDir: MONITOR_DIR, fileName: primaryFile, window, nowMs });
    return { readable: filesScanned.length > 0, missing: filesScanned.length === 0, events, filesScanned, filesSkipped: skipped, window, freshness };
  }

  /** 一行 = 一个（同伴 × 目标）组合。所有指标都挂在这行上。 */
  function makeRow(companionId, goalKey, goalLabel, goalSource) {
    return {
      key: companionId + "::" + goalKey,
      companionId,
      goalKey,
      goalLabel,
      goalSource,                       // goalId / description / objective / unknown
      counts: emptyCounts(),
      derived: { deathsPer100Actions: null, stepFailRate: null, actionsPerTurn: null },
      deaths: [],                       // 死亡明细（有上限）
      causes: {},                       // 死因分布
      tools: {},                        // 工具分布
      tasks: {},                        // 任务类型分布
      acNames: {},                      // AC 脚本分布
      learnedMaturity: {},              // 经验成熟度分布
      knowledgeChars: 0,                // 注入的规划知识字符数
      attribution: { attributed_events: 0, inferred_single: 0, inferred_leader: 0, ambiguous: 0, unattributed: 0 },
      // firstMs/lastMs 是**毫秒数**（内部比对用）；firstAt/lastAt 是 ISO 字符串。
      // ★ 这两个字段必须分开：混用过一次 —— 服务端存了 ms，前端按 ISO 字符串
      //   去显示，于是交叉表里出现「54169 ?」这种既不是时间也不是错误的东西。
      //   本工程对外一律给 ISO（别的地方都是这样），内部比对一律用 ms。
      firstAt: null,
      lastAt: null,
      firstMs: null,
      lastMs: null,
      points: [],                       // 归属到本行的事件时刻（稀疏序列的原料）
    };
  }

  /** 计数 + 记序列点 + 更新时间范围。 */
  function bump(r, ids, ts) {
    for (const id of ids) {
      if (r.counts[id] == null) continue;
      r.counts[id] += 1;
      r.points.push({ ts, m: id });
    }
    if (r.firstMs == null || ts < r.firstMs) r.firstMs = ts;
    if (r.lastMs == null || ts > r.lastMs) r.lastMs = ts;
  }

  return {
    async all() {
      const nowMs = Date.now();
      if (cache && nowMs - cache.at < CACHE_MS) return cache.data;

      const sources = {};
      for (const spec of SOURCES) sources[spec.key] = await readSource(spec, nowMs);

      // ── 目标时间轴 ─────────────────────────────────────────
      const timeline = buildGoalTimeline(sources.rdd.events);
      const allSegments = [];
      for (const c of timeline.companions.values()) for (const s of c.segments) allSegments.push(s);
      allSegments.sort((a, b) => a.startedAt - b.startedAt);

      // 同伴名提示：binding.json / stats.json 里**没有名字字段**，
      // 名字只出现在死因文本里 ⇒ 只能给「提示」，页面必须标出它的来源。
      const nameHints = inferNameHints(sources.instrumentation.events);

      const rows = new Map();
      const getRow = (cid, goalKey) => {
        const key = cid + "::" + goalKey;
        let r = rows.get(key);
        if (!r) {
          const g = timeline.goals.get(goalKey);
          r = makeRow(cid, goalKey, g ? g.label : (goalKey === UNKNOWN_GOAL ? UNKNOWN_GOAL_LABEL : goalKey), g ? g.source : "unknown");
          rows.set(key, r);
        }
        return r;
      };

      /** 有同伴字段的事件 ⇒ 归属是**事实**。d 显式传进来，不靠外部变量（踩过一次就够了）。 */
      const noteEvent = (cid, ts, ids, d, extra) => {
        const c = timeline.companions.get(cid);
        const seg = c ? goalAt(c, ts) : null;
        const r = getRow(cid, seg ? seg.goalKey : UNKNOWN_GOAL);
        r.attribution.attributed_events += 1;
        recordActivity(cid, ts);
        bump(r, ids, ts);
        if (extra) extra(r, d);
        return r;
      };

      // ★ 「每一个目标」必须包括**一个记录都没有的目标**。
      //   只在有事件时才建行的话，「这个目标下什么都没做」与「这个目标根本不存在」
      //   在页面上长得一模一样 —— 而这两件事要回答的问题完全不同。
      //   所以先把每个（同伴 × 目标段）都建出来，没事件的那些就是一片 0，
      //   页面照样显示「这个目标开着，但这一段没有任何记录」。
      for (const c of timeline.companions.values()) {
        for (const seg of c.segments) getRow(c.id, seg.goalKey);
      }

      // 每只同伴的活动时刻（后面「最近活动领先者」那档推断要用）。
      // ★ 必须用**严格早于 ts** 的最近一次，不能用「到现在为止最近的一次」——
      //   否则先处理完的所有后续事件会把早先那条 AC 事件也拉到那只同伴头上。
      const activity = new Map();   // cid → number[]（事件结束后排序）
      function recordActivity(cid, ts) {
        let arr = activity.get(cid);
        if (!arr) { arr = []; activity.set(cid, arr); }
        arr.push(ts);
      }
      function lastActivityBefore(cid, ts) {
        const arr = activity.get(cid);
        if (!arr || !arr.length) return null;
        let lo = 0, hi = arr.length - 1, hit = null;
        while (lo <= hi) {
          const mid = (lo + hi) >> 1;
          if (arr[mid] < ts) { hit = arr[mid]; lo = mid + 1; } else { hi = mid - 1; }
        }
        return hit;
      }

      // 埋点
      for (const e of sources.instrumentation.events) {
        const ts = eventTs(e);
        const cid = companionOf(e);
        if (ts == null || !cid) continue;
        const d = body(e);
        if (e.type === "death") {
          noteEvent(cid, ts, ["deaths"], d, (r) => {
            // 死因文案优先；都没有时**明说未标注**，
            // 不许拿 deathKind='other' 冒充死因（那不是死因，是分类）。
            const cause = d.deathCauseMsg || d.deathCauseId
              || (d.deathKind && d.deathKind !== "other" ? d.deathKind : "")
              || "（未标注死因）";
            r.causes[cause] = (r.causes[cause] || 0) + 1;
            if (r.deaths.length < 40) {
              r.deaths.push({
                at: e.timestamp, cause,
                kind: d.deathKind || "", attacker: d.deathAttacker || "",
                // ★ 实测 data.task 是空串 —— 页面必须知道「事件自带的目标」是空的
                task: d.task == null ? "" : String(d.task),
              });
            }
          });
        } else if (e.type === "loop_detected") {
          noteEvent(cid, ts, ["loops"], d);
        } else if (e.type === "asset_mismatch") {
          noteEvent(cid, ts, ["assetMismatch"], d);
        } else if (e.type === "recovery_failed") {
          // 恢复失败没有可累加的指标，但**必须出现**——否则「没恢复成功」看起来像「没发生过」
          const c = timeline.companions.get(cid);
          const seg = c ? goalAt(c, ts) : null;
          const r = getRow(cid, seg ? seg.goalKey : UNKNOWN_GOAL);
          r.recoveryFailed = (r.recoveryFailed || 0) + 1;
          recordActivity(cid, ts);
        }
      }

      // 工具调用
      for (const e of sources.tools.events) {
        const ts = eventTs(e);
        const cid = companionOf(e);
        if (ts == null || !cid) continue;
        const d = body(e);
        if (e.type === "tool_call") {
          noteEvent(cid, ts, ["actions"], d, (r) => {
            const t = d.tool || "?";
            r.tools[t] = (r.tools[t] || 0) + 1;
          });
        } else if (e.type === "tool_rejected") {
          noteEvent(cid, ts, ["rejected"], d);
        }
      }

      // 任务结果
      for (const e of sources.events.events) {
        const ts = eventTs(e);
        const cid = companionOf(e);
        if (ts == null || !cid || e.type !== "task_finished") continue;
        const d = body(e);
        if (String(d.status || "").toLowerCase() !== "done") continue;
        noteEvent(cid, ts, ["tasksDone"], d, (r) => {
          const t = d.task || "?";
          r.tasks[t] = (r.tasks[t] || 0) + 1;
        });
      }

      // AI 回合
      for (const e of sources.ai.events) {
        const ts = eventTs(e);
        const cid = companionOf(e);
        if (ts == null || !cid || e.type !== "turn") continue;
        noteEvent(cid, ts, ["turns"], body(e));
      }

      // RDD 自身的信号（支线卡住 / 重复死亡）
      for (const e of sources.rdd.events) {
        const ts = eventTs(e);
        const cid = companionOf(e);
        if (ts == null || !cid) continue;
        if (e.type === "subtask_stalled") noteEvent(cid, ts, ["stalls"], body(e));
        else if (e.type === "repeat_death_nearby") noteEvent(cid, ts, ["repeatDeaths"], body(e));
      }

      // 经验召回 / 规划知识（有同伴字段）
      for (const e of sources.expmem.events) {
        const ts = eventTs(e);
        const cid = companionOf(e);
        if (ts == null || !cid) continue;
        const d = body(e);
        if (e.type === "recalled") noteEvent(cid, ts, ["recalls"], d);
        else if (e.type === "planning_knowledge") {
          const c = timeline.companions.get(cid);
          const seg = c ? goalAt(c, ts) : null;
          const r = getRow(cid, seg ? seg.goalKey : UNKNOWN_GOAL);
          r.knowledgeChars += num(d.knowledgeChars) || 0;
          recordActivity(cid, ts);
        }
      }
      // 学习者复盘（有同伴字段）
      for (const e of sources.learner.events) {
        const ts = eventTs(e);
        const cid = companionOf(e);
        if (ts == null || !cid || e.type !== "reviewed") continue;
        noteEvent(cid, ts, ["reviews"], body(e));
      }

      // ── 没有同伴字段的事件 ⇒ 归属是**推断**（文件头〔1〕）──
      let unattributed = 0;
      let ambiguous = 0;
      let acxRuns = 0, acxSteps = 0, acxStepFails = 0, acxRunFails = 0;
      // 活动时刻表要先排好序，否则上面的二分查的是乱的
      for (const arr of activity.values()) arr.sort((a, b) => a - b);

      /**
       * 归属一条没有同伴字段的事件。
       * ★ 返回该行（或 null）。三档分别计数，**绝不把歧义塞进任何一行** ——
       *   塞进去等于编造归属，而那是本页唯一不能犯的错。
       */
      const inferEvent = (ts, ids, extra) => {
        if (ts == null) return null;
        const open = openSegmentsAt(allSegments, ts);
        if (open.length === 1) {
          const seg = open[0];
          const r = getRow(seg.companionId, seg.goalKey);
          r.attribution.inferred_single += ids.length;
          bump(r, ids, ts);
          if (extra) extra(r);
          return r;
        }
        if (open.length === 0) { unattributed += ids.length; if (extra) extra(null); return null; }
        // 多只开着 ⇒ 看谁刚活动过；领先超过阈值才认，否则不猜
        let best = null, bestAt = null, secondAt = null;
        let comparable = true;      // ★ 有一只「完全查不到它之前活动过」就不能比
        for (const seg of open) {
          const at2 = lastActivityBefore(seg.companionId, ts);
          // 这只同伴在那一刻之前一条带同伴字段的事件都没有 ⇒ 我们不知道它是不是
          // 那个 actor ⇒ 不能拿「它没证据」当成「它没在跑」。
          if (at2 == null) { comparable = false; continue; }
          if (bestAt == null || at2 > bestAt) { secondAt = bestAt; bestAt = at2; best = seg; }
          else if (secondAt == null || at2 > secondAt) { secondAt = at2; }
        }
        if (comparable && best && bestAt - secondAt > AC_LEAD_MS) {
          const r = getRow(best.companionId, best.goalKey);
          r.attribution.inferred_leader += ids.length;
          bump(r, ids, ts);
          if (extra) extra(r);
          return r;
        }
        ambiguous += ids.length;
        if (extra) extra(null);
        return null;
      };

      for (const e of sources.acx.events) {
        const ts = eventTs(e);
        const d = body(e);
        const kind = d.kind || "";
        if (ts == null) continue;
        if (kind === "RUN_STARTED") {
          acxRuns += 1;
          inferEvent(ts, ["acRuns"], (r) => { if (r) { const n = d.ac_name || "?"; r.acNames[n] = (r.acNames[n] || 0) + 1; } });
        } else if (kind === "STEP_STARTED") {
          acxSteps += 1;
          inferEvent(ts, ["acSteps"]);
        } else if (kind === "STEP_FAILED") {
          acxStepFails += 1;
          inferEvent(ts, ["acStepFails"]);
        } else if (kind === "RUN_FINISHED" && String(d.status || "") === "FAIL") {
          acxRunFails += 1;
        }
      }

      // expmem.learned 也没有同伴字段（实测键集 id/title/maturity/type/seven_filled）
      let memosLearnedTotal = 0;
      for (const e of sources.expmem.events) {
        const ts = eventTs(e);
        if (e.type !== "learned" || ts == null) continue;
        const d = body(e);
        memosLearnedTotal += 1;
        inferEvent(ts, ["memosLearned"], (r) => {
          if (!r) return;
          const m = d.maturity || "未标注";
          r.learnedMaturity[m] = (r.learnedMaturity[m] || 0) + 1;
        });
      }

      const acJournalEvents = sources.ac.events.length;

      // ── 汇总成数组并排序 ───────────────────────────────────
      const rowList = [...rows.values()];
      for (const r of rowList) {
        Object.assign(r.derived, derived(r.counts));
        r.points.sort((a, b) => a.ts - b.ts);
        r.deaths.sort((a, b) => (parseTs(b.at) || 0) - (parseTs(a.at) || 0));
        // 对外只给 ISO（见 makeRow 里那段注释）
        r.firstAt = isoOrNull(r.firstMs);
        r.lastAt = isoOrNull(r.lastMs);
      }
      rowList.sort((a, b) => (b.lastMs || 0) - (a.lastMs || 0));

      const seriesData = buildSeries(rowList);
      const learning = buildLearning(rowList);
      // ★ points 是服务端内部的稀疏原料（每个事件一个 {ts,metric}），
      //   留到响应里会让 payload 大出一个数量级（实测 3 万多个对象 / 300KB+）。
      //   曲线已经由 series 表达，points 出门前必须摘掉。
      for (const r of rowList) delete r.points;

      // ── 死因汇总 ─────────────────────────────────────────
      const attributionTotal = { attributed_events: 0, inferred_single: 0, inferred_leader: 0, ambiguous: 0, unattributed: 0 };
      for (const r of rowList) {
        const a = r.attribution || {};
        attributionTotal.attributed_events += a.attributed_events || 0;
        attributionTotal.inferred_single += a.inferred_single || 0;
        attributionTotal.inferred_leader += a.inferred_leader || 0;
      }
      const causesAll = {};
      for (const r of rowList) for (const [k, v] of Object.entries(r.causes)) causesAll[k] = (causesAll[k] || 0) + v;
      const causes = Object.entries(causesAll).map(([cause, count]) => ({ cause, count })).sort((a, b) => b.count - a.count);

      // ★ 同伴名单必须取**所有来源的并集**，不能只取 rdd 里出现过的 ——
      //   实测只有被监督的那只 AI 写过 taskchain_snapshot，
      //   只按 rdd 列同伴的话，另外 6 只会从名单里整个消失，
      //   而它们的死亡 / 行动恰恰是最需要被看见的。
      const allCompanionIds = new Set(timeline.companions.keys());
      for (const r of rowList) allCompanionIds.add(r.companionId);
      for (const e of sources.instrumentation.events) { const c = companionOf(e); if (c) allCompanionIds.add(c); }

      // ── 「目标」这件事本身接上了多少（页面必须先说这个）──
      //   实测：只有被 RDD 监督的那只 AI 有 taskchain_snapshot，
      //   其它同伴的死亡/行动**结构上就归不到目标上**。
      //   把它做成一个显式读数，而不是让读者从一堆「（目标未记录）」里自己猜。
      const companionIds = [...allCompanionIds];
      const withGoals = companionIds.filter((id) => {
        const c = timeline.companions.get(id);
        return !!(c && c.segments.length > 0);
      });
      const unknownRows = rowList.filter((r) => r.goalKey === UNKNOWN_GOAL);
      const unknownEvents = unknownRows.reduce((a, r) => a + r.attribution.attributed_events + r.attribution.inferred_single, 0);
      const totalEvents = rowList.reduce((a, r) => a + r.attribution.attributed_events + r.attribution.inferred_single, 0);
      const goalCoverage = {
        companions_total: companionIds.length,
        companions_with_goals: withGoals.length,
        companions_without_goals: companionIds.length - withGoals.length,
        rows_total: rowList.length,
        rows_unknown_goal: unknownRows.length,
        events_total: totalEvents,
        events_in_unknown_goal: unknownEvents,
        unknown_percent: totalEvents > 0 ? Math.round((unknownEvents / totalEvents) * 100) : null,
        note_zh: "只有被 RDD 监督、写过 taskchain_snapshot 的那只 AI 有「目标」。"
          + "其余同伴的死亡与行动**结构上就归不到任何目标**，本页把它们单列成「目标未接入」的行 —— "
          + "那一行的数字是「这段时间的表现」，不是「某个目标下的表现」。",
      };

      // ── 覆盖与新鲜度 ─────────────────────────────────────
      const coverage = {
        sources: SOURCES.map((s) => {
          const got = sources[s.key];
          return {
            key: s.key, label: s.label, file: s.file,
            readable: got.readable, missing: got.missing,
            eventsRead: got.events.length,
            filesScanned: got.filesScanned, filesSkipped: got.filesSkipped,
            truncated: got.filesScanned.some((f) => f.truncated),
            verdict: got.freshness ? got.freshness.verdict : null,
            verdict_zh: got.freshness ? got.freshness.verdict_zh : null,
            secondsSinceWrite: got.freshness ? got.freshness.seconds_since_this_file_write : null,
          };
        }),
        firstEventAt: isoOrNull(rowList.length ? rowList.reduce((m, r) => (r.firstMs != null && r.firstMs < m ? r.firstMs : m), Infinity) : NaN),
        lastEventAt: isoOrNull(rowList.length ? rowList.reduce((m, r) => (r.lastMs != null && r.lastMs > m ? r.lastMs : m), 0) : NaN),
        note_zh: "每个来源只读尾部固定字节数。标了「已截断」的来源，它的数字只是最近这一段，不是全量。",
      };

      // 页面顶部总新鲜度条：取**所有来源里最旧的那一份**的判读。
      // 一个页面只要有一个来源没跟上，整页就该被提醒。
      const verdicts = coverage.sources.filter((s) => s.verdict).map((s) => s.verdict);
      const worstVerdict = verdicts.includes("unreadable") ? "unreadable"
        : verdicts.includes("old_and_lagging") ? "old_and_lagging"
          : verdicts.includes("old_while_game_alive") ? "old_while_game_alive"
            : verdicts.includes("old_but_consistent") ? "old_but_consistent" : "fresh";
      const rddPrimary = sources.rdd.filesScanned[0] ? sources.rdd.filesScanned[0].file : "rdd.jsonl";
      const baseFresh = await freshnessOf({ monitorDir: MONITOR_DIR, fileName: rddPrimary, window: sources.rdd.window, nowMs });
      const VERDICT_ZH = {
        unreadable: "★ 有数据源读不到 —— 页面上标了「未接入」的格子是真的没有数据，不是 0",
        old_and_lagging: "★ 至少一个数据源早就断了 —— 下面的数字不是当前这一局的",
        old_while_game_alive: "★ 游戏还在写，但至少一个数据源很久没更新 —— 它的数字不是现在的",
        old_but_consistent: "游戏和这些数据一起停的 —— 数据自洽，但已经是过去的情况",
        fresh: "所有数据源都是新的",
      };
      const freshness = Object.assign({}, baseFresh, {
        verdict: worstVerdict,
        verdict_zh: VERDICT_ZH[worstVerdict],
        per_source: coverage.sources.map((s) => ({ key: s.key, label: s.label, verdict: s.verdict, readable: s.readable })),
      });

      const emptyReasons = [];
      if (!rowList.length) emptyReasons.push("既没读到带同伴字段的事件，也没读到目标快照 —— 可能游戏没在跑，或这个 monitor 目录不是这一局的。");
      if (!sources.instrumentation.readable) emptyReasons.push("读不到 instrumentation.jsonl ⇒ 死亡 / 空转 / 资产不匹配全是「未接入」。");
      if (!sources.acx.readable) emptyReasons.push("读不到 acx.jsonl ⇒ AC 调用次数全是「未接入」。");
      if (!sources.rdd.readable) emptyReasons.push("读不到 rdd.jsonl ⇒ 目标无法确定，每只 AI 都只有一行「（目标未记录）」。");
      if (timeline.companions.size === 0) emptyReasons.push("没有识别出任何同伴（事件里一个 companionId 都没有）—— 这一页按「每只 AI」分组的能力就没有数据支撑。");

      // ★ 同伴名单已在上面取好并集。
      const data = {
        ok: true,
        generatedAt: isoOrNull(nowMs),
        monitorDir: MONITOR_DIR,
        metrics: METRICS,
        companions: [...allCompanionIds].map((id) => {
          const c = timeline.companions.get(id);
          return {
            id,
            nameHint: nameHints[id] || null,
            goals: c ? c.segments.length : 0,
            has_goal_record: !!(c && c.segments.length > 0),
            firstSeenAt: isoOrNull(c ? (c.segments.length ? c.segments[0].startedAt : c.lastAt) : null),
            lastSeenAt: isoOrNull(c ? c.lastAt : null),
            sawTaskchainSnapshot: !!(c && c.sawSnapshot),
            segments: c ? c.segments.map((s) => ({
              goalKey: s.goalKey,
              source: s.source,
              startedAt: isoOrNull(s.startedAt),
              endedAt: isoOrNull(s.endedAt),
            })) : [],
          };
        }).sort((a, b) => (b.lastSeenAt || "").localeCompare(a.lastSeenAt || "")),
        goals: [...timeline.goals.values()]
          .map((g) => ({ key: g.key, label: g.label, source: g.source, snapshots: g.snapshots, lastAt: isoOrNull(g.lastAt) }))
          .sort((a, b) => (b.lastAt || "").localeCompare(a.lastAt || "")),
        unknownGoal: { key: UNKNOWN_GOAL, label: UNKNOWN_GOAL_LABEL },
        goalCoverage,
        rows: rowList,
        series: seriesData,
        learning,
        causes,
        memosLearnedTotal,
        ac: {
          runs: acxRuns,
          steps: acxSteps,
          stepFails: acxStepFails,
          runFails: acxRunFails,
          journalEvents: acJournalEvents,
          inferredSingle: attributionTotal.inferred_single,
          inferredLeader: attributionTotal.inferred_leader,
          ambiguousEvents: ambiguous,
          unattributedEvents: unattributed,
          leadThresholdMs: AC_LEAD_MS,
          readable: sources.acx.readable,
          note_zh: "AC 与「经验新增」事件本身没有同伴字段。归属分三档："
            + "① 那一刻只有一只同伴的目标段开着 ⇒ 归给它（强推断）；"
            + "② 多只同时开着 ⇒ 看谁在那一刻之前刚活动过，领先超过 " + (AC_LEAD_MS / 1000)
            + " 秒才归给它（弱推断，标「推断」）；"
            + "③ 领先不够 / 一只都没开 ⇒ 不猜，记为「无法判定」或「未归属」，不计入任何一行。",
        },
        coverage,
        freshness,
        caveats: [
          { id: "ac-attribution-inferred", note_zh: "AC 调用次数按同伴/目标的归属是**时间线推断**（acx 事件没有同伴字段），不是记录里的事实。" },
          { id: "death-task-empty", note_zh: "死亡事件自带的目标字段（data.task）实测是空串，所以死亡的目标归属同样是推断。" },
          { id: "two-ac-journals", note_zh: "acx.jsonl 与 ac.jsonl 是同一件事的两个日志，本页只用 acx 出指标；ac 的 " + acJournalEvents + " 条只作参考，两者不可相加。" },
          { id: "goal-not-subtask", note_zh: "「目标」按 goalId 切段，不按小步切 —— 实测同一目标里 currentSubtaskId 每几秒变一次，按它切会把一个目标切成上百段。" },
          { id: "goal-unknown-bucket", note_zh: "没有目标快照的那段时间归到「（目标未记录）」。**那一行不是「某个目标下的表现」**，只是「这段时间的表现」。" },
          { id: "window-truncated", note_zh: "每个来源只读尾部固定字节数。标了「已截断」的来源，其数字只是最近这一段。" },
          { id: "gaps-are-not-zero", note_zh: "曲线里值为 0 的桶是「读了那一段、一条记录都没有」；被标成 gap 的桶是「那一段根本没读到」。两者含义不同。" },
          { id: "name-hint-is-inferred", note_zh: "同伴的显示名**引擎没有存**（binding.json / stats.json 里都没有），本页的名字是从死因文本里猜的，属于提示，不是注册名。" },
        ],
        emptyReasons,
      };
      cache = { at: nowMs, data };
      return data;
    },
  };
}

/**
 * 死因文本里能不能读出同伴名字。
 *
 * ★ 实测 `companions/<uuid>/binding.json` 只有 provider、`stats.json` 只有 token 台账，
 *   引擎没有任何地方写「注册名」。唯一带名字的地方是死因文本
 *   （`deathCauseMsg: "DDD被骷髅射杀"`）⇒ 只能当**提示**用。
 */
function inferNameHints(instrEvents) {
  const votes = new Map();
  for (const e of instrEvents) {
    if (e.type !== "death") continue;
    const d = body(e);
    const cid = companionOf(e);
    if (!cid) continue;
    const msg = String(d.deathCauseMsg || "");
    // 实测文案形如「DDD被骷髅射杀」「ACX被女仆妖精的弹幕打得满身疮痍」
    // ⇒ 名字在句首、后面紧跟动词。名字**可以是 ASCII**（DDD / ACX 都是）。
    const m = msg.match(/^(.{1,16}?)[被死于杀死淹烧摔]/);
    if (!m) continue;
    const name = m[1].trim();
    if (!name || /^(他|她|它|我|你|该同伴|other|unknown|)$/i.test(name)) continue;
    if (!votes.has(cid)) votes.set(cid, new Map());
    votes.get(cid).set(name, (votes.get(cid).get(name) || 0) + 1);
  }
  const out = {};
  for (const [cid, names] of votes) {
    let best = null, bestN = 0;
    for (const [n, c] of names) if (c > bestN) { best = n; bestN = c; }
    if (best) out[cid] = best;
  }
  return out;
}

/**
 * 序列：连续桶 + gaps。
 *
 * 只给**有事件**的序列（稀疏点），不给全量笛卡尔积 ——
 * 页面上「这个目标下的曲线」只需要它自己那几个桶。
 */
function buildSeries(rowList) {
  let minTs = Infinity, maxTs = 0;
  for (const r of rowList) {
    if (r.firstMs != null && r.firstMs < minTs) minTs = r.firstMs;
    if (r.lastMs != null && r.lastMs > maxTs) maxTs = r.lastMs;
  }
  if (!Number.isFinite(minTs) || maxTs === 0) {
    return { bucketMs: 60e3, bucketLabel: "1 分钟", buckets: [], rows: [], note_zh: "没有任何带时间戳的事件。" };
  }
  const b = chooseBucket(Math.max(60000, maxTs - minTs));
  const count = Math.min(MAX_BUCKETS, Math.max(1, Math.floor(maxTs / b.ms) - Math.floor(minTs / b.ms) + 1));
  const offset = Math.max(0, Math.floor(maxTs / b.ms) - count + 1);

  const buckets = [];
  const bucketIndex = new Map();
  for (let i = 0; i < count; i++) {
    const ts = (offset + i) * b.ms;
    buckets.push({ iso: isoOrNull(ts), ts });
    bucketIndex.set(ts, i);
  }

  const rows = [];
  let outsideTotal = 0;
  for (const r of rowList) {
    const byMetric = new Map();        // bucketIndex → { metricId → n }
    let inRange = 0;
    for (const hit of r.points) {
      const bi = bucketIndex.get(bucketStart(hit.ts, b.ms));
      if (bi == null) continue;        // 落在网格外（桶数封顶）⇒ 不画，但要报数量
      inRange += 1;
      let cell = byMetric.get(bi);
      if (!cell) { cell = {}; byMetric.set(bi, cell); }
      cell[hit.m] = (cell[hit.m] || 0) + 1;
    }
    if (!byMetric.size) continue;
    const cells = {};
    for (const [bi, cell] of byMetric) {
      for (const m of Object.keys(cell)) {
        if (!cells[m]) cells[m] = {};
        cells[m][String(bi)] = cell[m];
      }
    }
    outsideTotal += r.points.length - inRange;
    rows.push({
      key: r.key, companionId: r.companionId, goalKey: r.goalKey, goalLabel: r.goalLabel,
      cells,
      bucketsWithData: byMetric.size,
      gaps: count - byMetric.size,
      pointsInRange: inRange,
      pointsOutsideRange: r.points.length - inRange,
    });
  }

  return {
    bucketMs: b.ms,
    bucketLabel: b.label,
    buckets,
    rows,
    pointsOutsideRange: outsideTotal,
    note_zh: "横轴每格 " + b.label + "。桶数上限 " + MAX_BUCKETS + "，"
      + (outsideTotal > 0 ? "有 " + outsideTotal + " 个事件落在网格之外（更早），那些不进曲线。" : "所有事件都在网格内。")
      + "格子里有数字 = 那一段读到了记录；没有数字的格子 = 那一段一条记录都没有（真的没在跑）。",
  };
}

/**
 * 学习曲线：把每个（同伴 × 目标）的行动按先后切成 4 段，
 * 看死亡率（每百次行动）是升还是降。
 *
 * ★ 为什么用「每百次行动」而不是「每次死亡隔多久」：
 *   后者会把「跑得久」当成「学得好」—— 挂机也能攒时长。
 *   行动量做分母，才看得出「同样干那么多事，死得少了」。
 * ★ 某段 actions=0 ⇒ deathsPer100Actions = null（不是 0），见文件头〔6〕。
 */
function buildLearning(rowList) {
  const SLICES = 4;
  const out = [];
  for (const r of rowList) {
    const totalActions = r.counts.actions;
    if (totalActions < 8) continue;       // 行动太少，切 4 段没意义
    // 按「第几次行动」分桶，而不是按时间 —— 避免某段全挤在头几秒里
    const per = [];
    for (let i = 0; i < SLICES; i++) per.push({ actions: 0, deaths: 0, learns: 0, acStepFails: 0 });
    let seen = 0;
    let firstAt = null, lastAt = null;
    for (const hit of r.points) {
      if (firstAt == null) firstAt = hit.ts;
      lastAt = hit.ts;
      if (hit.m === "actions") {
        const s = Math.min(SLICES - 1, Math.floor((seen / totalActions) * SLICES));
        per[s].actions += 1;
        seen += 1;
        continue;
      }
      // 事件先落在当前还没跨段的桶里
      const s = Math.min(SLICES - 1, Math.floor((seen / totalActions) * SLICES));
      if (hit.m === "deaths") per[s].deaths += 1;
      else if (hit.m === "acStepFails") per[s].acStepFails += 1;
      else if (hit.m === "recalls" || hit.m === "reviews" || hit.m === "memosLearned") per[s].learns += 1;
    }
    if (totalActions === 0) continue;
    const slices = per.map((p, i) => ({
      index: i,
      label: "第 " + (i + 1) + " 段",
      actions: p.actions,
      deaths: p.deaths,
      acStepFails: p.acStepFails,
      learns: p.learns,
      deathsPer100Actions: p.actions > 0 ? Math.round((p.deaths / p.actions) * 1000) / 10 : null,
    }));
    const firstRate = slices[0].deathsPer100Actions;
    const lastRate = slices[SLICES - 1].deathsPer100Actions;
    out.push({
      key: r.key, companionId: r.companionId, goalKey: r.goalKey, goalLabel: r.goalLabel,
      slices,
      firstAt: isoOrNull(firstAt), lastAt: isoOrNull(lastAt),
      totalActions, totalDeaths: r.counts.deaths,
      // null = 头或尾有一段没有行动，算不出方向；页面必须显示「?」而不是「没变好」
      trend: firstRate == null || lastRate == null ? null : Math.round((lastRate - firstRate) * 10) / 10,
    });
  }
  return {
    slices: SLICES,
    rows: out,
    note_zh: "把同一目标下的行动次数按先后切成 " + SLICES + " 段，看「每百次行动死了几次」是升还是降。"
      + "分母用行动次数而不是时长 —— 挂机也能攒时长，那不算学。"
      + "trend 为负 = 后段死得更少；trend 显示 ? = 有一段没有行动，算不出方向。",
  };
}