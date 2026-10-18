// ─────────────────────────────────────────────────────────────────────────────
// 经验链数据流视图（B18）——「这些数字连起来对不对」的目视验收面
//
// 为什么单独一页而不是塞进 chain-view：
//   chain-view 的既有形状是「一堆卡片各说各的」（death/planning/injection/…），
//   每一张都在报自己那块的读数，但**没有任何东西把它们连起来**。用户要的正是
//   「数据流是否正确」—— 那是一个**拓扑**问题，不是六个独立指标问题。
//   同一批数据，两种看法能得出相反的结论：五张卡都绿 ≠ 链是通的
//   （链条可以每张都好看、而中间某条边一次都没被走过）。
//
// ★ 本文件只做「读 + 组装」，不写任何东西。零副作用。
//
// ★ 设计红线（三条，写在这里免得后人改掉）：
//   1. **断边要显式画出来，不许用「0 条」暗示。**
//      「0」有三种含义（真没有 / 采样窗口没覆盖 / 上游断了读不到），
//      这页在每条边上都标了属于哪一种，`state: "blind"` 就是第三种。
//   2. **盲区写进图里，不藏。** tools.jsonl 不记 find_tools 这件事，
//      在既有 chain-view 里完全看不见（实测该文件 0 行、而内脑 chat.jsonl 里
//      52 次）⇒ 监测台此前一直在漏报「内脑去取工具」这一步。
//   3. **每个节点都要能展开看内容**，不只是看计数。用户原话：
//      「至少让我看看内容那些，确认一下」。计数证明不了「内容对不对」。
// ─────────────────────────────────────────────────────────────────────────────
import { resolve } from "node:path";
import { readJsonlWindow } from "./tail-jsonl.mjs";
import { createToolAttributionView } from "./tool-attribution.mjs";
import { STATE, measure, unobservable, unknown, known, auditColumn } from "./unknown-vs-zero.mjs";
import { readExperienceStoreView, readLearnerView } from "./chain-view.mjs";
import { freshnessOf } from "./freshness.mjs";

// ★ 采样上限改成**按文件给**，不是一刀切（第 5 组红线「历史 ≠ 当前」，2026-10-04）
//
// 为什么必须改：一刀切 4MB/500 条时，本页「⑦ 任务结果」节点显示 28 条
// task_finished，而文件里实际有 64 条 —— 因为 events.jsonl 涨到 7.6MB 之后
// 4MB 的窗口只覆盖了尾部一半。**页面上没有任何地方说这件事**，
// 于是「文件变大 ⇒ 数字静默变错」，正是本组红线要治的那个病。
//
// 依据：`monitor/*.jsonl` 在 16MB 轮转（MonitoringJournal:29/130）。
// 凡是「条数本身就是这一环的产品」的节点，上限要够覆盖一个完整轮转周期，
// 否则它永远在慢慢失真。
const SAMPLING = {
  // 事件源：节点①的条数就是产品。实测已 7.6MB，16MB = 一个轮转周期
  "instrumentation.jsonl": { maxBytes: 16 * 1024 * 1024, maxEvents: 40000 },
  // 结果源：节点⑦的条数 + outcomes.byStatus 都是产品。实测已 7.6MB
  "events.jsonl": { maxBytes: 16 * 1024 * 1024, maxEvents: 40000 },
  // 门禁时间线：实测 1.0MB，增长慢，但要能画够长的线
  "brain.jsonl": { maxBytes: 8 * 1024 * 1024, maxEvents: 5000 },
  // ⚠️ tools.jsonl 刻意**不抬**：实测 11.3MB。本页只用它回答
  // 「find_tools 有没有被记下来」这一个是非题，而下面的 findTools 读数
  // 会在截断时**主动降级那句话的口气**（说「最近 N 条里没有」而不是
  // 「整个文件里 0 行」）。抬上限只会让这句话今天恰好为真、一周后悄悄变假。
  "tools.jsonl": { maxBytes: 4 * 1024 * 1024, maxEvents: 500 },
};
const DEFAULT_SAMPLING = { maxBytes: 4 * 1024 * 1024, maxEvents: 500 };
const samplingOf = (name) => SAMPLING[name] || DEFAULT_SAMPLING;

/** 窗口事实给前端的形状；读不到时如实说 null，不补一个假的「0」。 */
const winFacts = (w) => (w ? {
  truncated: w.truncated,
  size_bytes: w.sizeBytes,
  window_bytes: w.windowBytes,
  bytes_dropped: w.bytesDropped,
  events_read: w.events.length,
  lines_in_window: w.linesInWindow,
  lines_dropped: w.linesDropped,
} : null);

/**
 * ★ 本页唯一的读法。**刻意不保留一个「只返回数组」的版本**：
 *   第一版有 `readJsonl`（返回 rows）与 `readWin`（返回窗口对象）两个函数，
 *   改完之后 `readJsonl` 就没有调用者了。留着它 = 留一个陷阱 ——
 *   后人拿它读文件就会**又绕开窗口事实**，本组红线白做。
 *   所以只留一个入口，要窗口事实就一定会拿到。
 *
 * `window: null` 表示文件读不到，freshness 据此把 verdict 判成 `unreadable`
 * 而不是 `fresh`（见 freshness.mjs 里的拦截）。
 */
async function readWin(file) {
  const lim = samplingOf(file.slice(file.lastIndexOf("\\") + 1).slice(file.lastIndexOf("/") + 1));
  try { return await readJsonlWindow(file, lim.maxBytes, lim.maxEvents); }
  catch { return null; }
}

const last = (rows) => (rows.length ? rows[rows.length - 1] : null);
// ★ 注意：readJsonlTail 返回的**已经是文件里最新的在最后**（按文件顺序），
//   所以 `last(rows)` 才是最新的那一条。若再 `.reverse()` 会把时间倒过来 ——
//   我第一版抄了 readDeathView 的 `slice(-20).reverse()` 用在 brain.jsonl 上，
//   结果时间线是倒着排的（03:15:49 排在 03:15:47 前面）。
//   正确做法：取尾部 N 条但**不反转**，或者先反转让 rows 变成「新的在前」再 slice。
const newestLast = (rows, n = 20) => rows.slice(-n);
const newestFirst = (rows, n = 20) => rows.slice(0, n);

/** 一个节点：count 是「有多少」，latest 是「最后一条是什么」，present 是「这一环有没有发生过」。 */
function node(id, label, source, rows, opts = {}) {
  const newest = last(rows);
  const at = newest && (newest.timestamp || (newest.data && newest.data.at));
  // ★★ 第 5 组红线第四条「未知 ≠ 零」：**未知的时候 count 必须是 null，不能是 0。**
    //   原因（实测）：本函数原来对「结构上不可观测」的三个节点照样算出 count=0，
    //   于是对象里同时存在 `present:false` 与 `count:0`。
    //   页面因为读了 present 才没被骗过去，但任何只看 count 的下游
    //   （未来的页、脚本、我自己下一次分析）都会把它读成「0 条」。
    //   ⇒ 未知在数据层就不能是一个数字。
  const rawCount = opts.count !== undefined ? opts.count : rows.length;
  const m = opts.measure || (
    rawCount === null || rawCount === undefined
      // 「没量过」与「量到 0」必须分得开：opts.count === undefined 且 rows 为空
      // 有两种可能 —— 数据源读到了但确实是空的，或者压根没量。
      // opts.count 显式给 null ⇒ 调用者明说「没量」；给数字 ⇒ 那个数字就是量出来的。
      ? (opts.count === null ? unknown("not_measured") : unobservable("no_sink_exists"))
      : known(rawCount));
  return {
    id, label, source,
    // ★ count 保留是给页面用的兼容字段，但它现在是 null 而不是 0。
    count: m.count,
    // measure 是这台机器可读的真相：state + reason + display。
    measure: m,
    present: opts.present !== undefined ? opts.present : rows.length > 0,
    lastAt: at || null,
    latest: opts.project ? opts.project(newest) : (newest ? newest.data || newest : null),
    sample: (opts.sample || newestLast(rows, opts.sampleSize || 20)).map((r) =>
      opts.project ? opts.project(r) : (r.data || r)),
    // ★ 不再默认 true。调用者必须明说；没说的按 rows 长度推断，
    //   而 rows 为空且没给 count ⇒ 那是「没量到」，不是「读到了 0 条」。
    readable: opts.readable !== undefined ? opts.readable : rows.length > 0,
  };
}

/** 一条边：from→to。它要说清「这条边有没有被走过」以及「凭什么这么判断」。 */
function edge(from, to, opts) {
  const { state, measured, evidence = [], why = "", blind = false } = opts;
  return { from, to, state, measured, evidence, why, blind };
}

export function createExperienceChainView(monitorDir) {
  const configDir = resolve(monitorDir, "..");
  // 「被提及 ≠ 被调用」的读数口。**刻意用实时读数而不是写死的数字** ——
  // 写死的「252 次」过一局就过期了，而过期的小数比没有更坏。
  // 它自带 8 秒缓存，而本页每 5 秒轮询一次 ⇒ 实际约每 10 秒扫一次。
  const attribution = createToolAttributionView(monitorDir, configDir);

  return {
    monitorDir,
    configDir,

    async all() {
      // ★ instr / events / brain 改用 readWin：同一次读取顺带拿到窗口事实
      //   （本次有没有截断、丢了几行）。learner / tools / store 仍用原函数 ——
      //   它们要么自带 read*View（内部自己读），要么本页不需要逐节点的新鲜度。
      const [instrWin, learnerRaw, eventsWin, brainWin, toolsWin, store, attributionResult] = await Promise.all([
        readWin(resolve(monitorDir, "instrumentation.jsonl")),
        readLearnerView(monitorDir),
        readWin(resolve(monitorDir, "events.jsonl")),
        readWin(resolve(monitorDir, "brain.jsonl")),
        readWin(resolve(monitorDir, "tools.jsonl")),
        readExperienceStoreView(configDir),
        // ★ 归因读数失败不许拖垮本页 —— 它是「补充说明」，不是本页的产品。
        //   .catch(() => null) 之后下游一律按「没有归因数据」渲染（显示 ? 而不是 0）。
        attribution.all().catch(() => null),
      ]);
      const attributionData = attributionResult;
      // 读不到时退回空数组，让下游的「0 条」路径照旧能跑；
      // 真正区分「读不到」的是 freshness 的 verdict（unreadable），不是这里。
      const instr = instrWin ? instrWin.events : [];
      const events = eventsWin ? eventsWin.events : [];
      const brain = brainWin ? brainWin.events : [];
      const tools = toolsWin ? toolsWin.events : [];

      // learner.jsonl 的 intake 埋点（B7 加的）不在 readLearnerView 的三个数组里，
      // 只能从 byType 里挖。写死数组名去取会永远取到 0 条 —— 那正是 60 号文档
      // §4.3 记的「0 的三种含义」里最危险的那种。
      const intakeRows = (learnerRaw.byType || []).find((t) => t.type === "intake");
      const noted = learnerRaw.byType?.find((t) => t.type === "noted")?.n ?? 0;
      const reviewed = learnerRaw.byType?.find((t) => t.type === "reviewed")?.n ?? 0;

      // 任务结果：B10 修完才有真 status。用精确匹配而不是 /finished/i ——
      // readDeathView 那个宽松正则漏过形态，新页不学它。
      const taskFinished = events.filter((r) => r.type === "task_finished");
      const withStatus = taskFinished.filter((r) => r.data && r.data.status);
      const byStatus = {};
      for (const r of withStatus) {
        const s = r.data.status;
        byStatus[s] = (byStatus[s] || 0) + 1;
      }

      // 门禁读数：十道闸里哪一道红。取最近 N 条给时间线。
      const gates = brain.filter((r) => r.type === "gate" || r.type === "external_take");

      // ★ find_tools 在 tools.jsonl 里是 0 行（实测），而内脑自己的 chat.jsonl 里
      //   几十次。所以「内脑主动去取工具」这一步监测台此前完全看不见。
      const findToolsRows = tools.filter((r) => r.tool === "find_tools");

      // ★ find_tools 的**真判据**来自内脑自己的对话，不是 tools.jsonl。
      //   归因读数失败 ⇒ 两个数都是 null ⇒ 下面退回「只有 tools.jsonl 那一说」，
      //   并在盲区说明里标「归因读数取不到」，而不是假装内脑没调过。
      const findToolsBrainCalls = attributionData
        ? ((attributionData.tools.find((t) => t.name === "find_tools") || {}).brain_calls || 0)
        : null;
      const findToolsJournalCalls = attributionData
        ? ((attributionData.tools.find((t) => t.name === "find_tools") || {}).journal_calls || 0)
        : null;
      const blindList = attributionData ? attributionData.blind_to_journal : null;

      // ★ 每个节点都带自己的新鲜度（第 5 组红线「历史 ≠ 当前」）。
      //   为什么必须逐节点而不是整页一个：本页九个节点读的是**九个不同的文件**
      //   （instrumentation / learner.jsonl / learner-memos-*.json / experience-*.jsonl /
      //     events.jsonl / brain.jsonl …），它们的停更时间可以差很远。
      //   实测就有：learner.jsonl 停在 10-03 16:49，而 context.jsonl 停在 10-04 01:56。
      //   整页一个新鲜度会把「某个节点很旧」藏起来。
      //   ⚠️ `dir` 指向 configDir 的那几个（候选队列 / 经验库）必须显式给 dir，
      //     但 `referenceDir` 恒为 monitorDir —— 参照系必须是引擎写的那些文件。
      const fAt = (fileName, opts) =>
        freshnessOf({ monitorDir, referenceDir: monitorDir, fileName, ...(opts || {}) });
      const freshOf = {
        events: await fAt("instrumentation.jsonl", { window: instrWin }),
        gate: await fAt("learner.jsonl"),
        queue: await fAt(
          (store.learnerMemos && store.learnerMemos[0] && store.learnerMemos[0].file) || "learner-memos-none.json",
          { dir: configDir }),
        noted: await fAt("learner.jsonl"),
        store: await fAt(
          (store.files && store.files[0] && store.files[0].file) || "experience-none.jsonl",
          { dir: configDir }),
        outcome: await fAt("events.jsonl", { window: eventsWin }),
        brain: await fAt("brain.jsonl", { window: brainWin }),
      };

      const nodes = [
        node("events", "① 事件", "instrumentation.jsonl", instr,
          { project: (r) => (r.data || {}), sampleSize: 20 }),
        node("gate", "② 判定", "CandidateGate T1–T7 / learner_intake", [], {
          present: (intakeRows?.n ?? 0) > 0, count: intakeRows?.n ?? 0,
          readable: true,
          latest: { note: "判定层本身不打日志；能观测的只有它的结果「intake 埋点条数」", intake: intakeRows?.n ?? 0 },
          sample: [], source: "learner.jsonl#intake（判定层唯一的可观测出口）",
        }),
        node("queue", "③ 候选队列", "learner-memos-<uuid>.json", [], {
          present: (store.learnerMemos || []).some((f) => (f.count || 0) > 0),
          count: (store.learnerMemos || []).reduce((a, f) => a + (f.count || 0), 0),
          readable: true,
          latest: (store.learnerMemos || []).flatMap((f) => (f.memos || []).slice(-1))[0] || null,
          sample: (store.learnerMemos || []).flatMap((f) => f.memos || []).slice(-20).reverse(),
          source: (store.learnerMemos || []).map((f) => `${f.file} (${f.count ?? "?"} 条)`).join(", ") || "没有 learner-memos-*.json",
        }),
        node("noted", "④ 复盘", "learner.jsonl#noted / #reviewed", [], {
          present: noted > 0, count: noted, readable: true,
          reviewed, latest: learnerRaw.noted?.[0] || null, sample: learnerRaw.noted || [],
          source: `learner.jsonl（noted ${noted} / reviewed ${reviewed}）`,
        }),
        node("store", "⑤ 落库", "experience-*.jsonl", [], {
          present: store.totalOnDisk > 0, count: store.totalOnDisk, readable: true,
          readableByStore: store.readableByStore,
          conflicts: store.conflicts || [],
          files: store.files || [],
          latest: null, sample: [],
          source: `totalOnDisk ${store.totalOnDisk} / Store 可读 ${store.readableByStore}`,
        }),
        node("recall", "⑥ 召回留痕", "PresentationReceipt（会话内，不落盘）", [], {
          present: false, count: null, readable: false, measure: unobservable("no_sink_exists"),
          latest: { note: "呈现回执是会话内记账，不落盘 ⇒ 监测台看不到它。要看得靠 experience_verify 省略 id 的只读清单（MCP 侧）" },
          sample: [], source: "★ 结构上不可观测：PresentationReceipt 只在内存里",
        }),
        node("outcome", "⑦ 任务结果", "events.jsonl#task_finished", taskFinished, {
          project: (r) => ({ timestamp: r.timestamp, id: r.data?.id, task: r.data?.task, status: r.data?.status }),
          sampleSize: 20,
        }),
        node("correlate", "⑧ 配对", "OutcomeCorrelator", [], {
          present: false, count: null, readable: false, measure: unobservable("no_sink_exists"),
          latest: { note: "配对结果只在 experience_verify 的返回值里（outcomes/correlation 读数），不落盘" },
          sample: [], source: "★ 结构上不可观测：只在工具返回值里",
        }),
        node("verify", "⑨ 回报", "learner.jsonl + expmem.jsonl", [], {
          present: false, count: null, readable: false, measure: unobservable("no_sink_exists"),
          latest: { note: "verify/retract 完全不埋点（ExperienceMonitor.publish 全仓只有 learned/planning_knowledge 两个调用点）" },
          sample: [], source: "★ 埋点缺口：verify/retract 没有写入任何 jsonl",
        }),
      ];

      // ★ 把逐节点的新鲜度挂上去。没有真实文件来源的三个节点
      //   （⑥召回留痕 / ⑧配对 / ⑨回报）拿不到 freshness ⇒ null，
      //   页面据此显示「结构上不可观测」，而不是去猜一个时间。
      for (const n of nodes) {
        n.freshness = freshOf[n.id] || null;
      }

      const edges = [
        edge("events", "gate", {
          state: "measured",
          measured: `${instr.length} 条事件 → intake ${intakeRows?.n ?? 0} 次`,
          evidence: ["monitor/instrumentation.jsonl", "monitor/learner.jsonl#intake"],
          why: "判定层读事件、决定要不要入队。intake 埋点数是这条边唯一可观测的痕迹。",
        }),
        edge("gate", "queue", {
          state: (intakeRows?.n ?? 0) > 0 ? "measured" : "broken",
          measured: `intake ${intakeRows?.n ?? 0} → 队列 ${(store.learnerMemos || []).reduce((a, f) => a + (f.count || 0), 0)} 条`,
          evidence: ["monitor/learner.jsonl#intake", "learner-memos-<uuid>.json"],
          why: "B7 实机：enqueued:2 / queue_depth:2，候选 ci-instr-1(T2/death) 与 ci-instr-2(T3 重复)",
        }),
        edge("queue", "noted", {
          state: noted > 0 ? "measured" : "unknown",
          measured: `队列 → noted ${noted} 条`,
          evidence: ["monitor/learner.jsonl#noted"],
          why: noted > 0 ? "队列里的备忘被 learner_note 取出来过" : "★ 从没走过：入队的候选还没被复盘",
        }),
        edge("noted", "store", {
          state: store.readableByStore > 0 ? "measured" : "broken",
          measured: `落库 totalOnDisk ${store.totalOnDisk} / Store 可读 ${store.readableByStore}`,
          evidence: ["experience-*.jsonl", "monitor/expmem.jsonl#learned"],
          why: (store.conflicts || []).length
            ? `⚠ ${store.conflicts.length} 个文件形状冲突（数组包着却叫 .jsonl）⇒ 代码读出来是 0 条`
            : "草稿→experience_learn→store 那一跳仍要 AI 自己走（RL-20：学习者不自己执行）",
        }),
        edge("store", "recall", {
          state: "blind",
          measured: "结构上不可观测",
          evidence: ["experience-core/core/PresentationReceipt.java"],
          why: "★ 呈现回执只存在内存里（session_only），不落盘 ⇒ 这一环在监测台上永远是空的。"
            + " 这不是「没发生」，是「发生了我也看不见」——两者必须分开。",
          blind: true,
        }),
        edge("recall", "outcome", {
          state: withStatus.length > 0 ? "measured" : "broken",
          measured: `task_finished ${taskFinished.length} 条，其中带 status 的 ${withStatus.length} 条`,
          evidence: ["monitor/events.jsonl#task_finished"],
          why: "B10 修完 NumenEvents.emit 才开始有 status。B10 之前 3349 条 task_finished 全无 status ⇒ 成败不可判。",
        }),
        edge("outcome", "correlate", {
          state: "blind",
          measured: "结构上不可观测",
          evidence: ["experience-core/core/OutcomeCorrelator.java"],
          why: "★ 配对结果只在 experience_verify 的返回值里，不落盘。"
            + " 实机那次 OUTCOME_KNOWN 是我从 MCP 侧调工具看到的，不是从日志里看到的。",
          blind: true,
        }),
        edge("correlate", "verify", {
          state: "blind",
          measured: "埋点缺口",
          evidence: ["plugins/experience/.../ExperienceMonitor.java"],
          why: "★ ExperienceMonitor.publish 全仓只有 learned / planning_knowledge 两个调用点 ⇒"
            + " verify/retract 完全不埋点。ESCAPED_NOTE 也只教 recall、不教回报。",
          blind: true,
        }),
        edge("recall", "gate", {
          state: findToolsRows.length > 0 ? "measured" : "blind",
          // ★ 这条边现在有**真判据**了：不再是「tools.jsonl 里 0 行 ⇒ 它没调过」，
          //   而是去内脑自己的对话里数 assistant.tool_calls。
          //   实测（2026-10-04）：find_tools 内脑真调过 252 次，而 tools.jsonl 里 0 行。
          //   ⇒ 这不是「没发生」，是**监测台看不见** —— 我此前正因为只看了 tools.jsonl
          //     而把它判成「它从没去取过工具」，错了三次。
          measured: findToolsBrainCalls
            ? `内脑自己的对话里 find_tools 真调用 ${findToolsBrainCalls} 次`
              + (findToolsJournalCalls
                  ? `，引擎日志 ${findToolsJournalCalls} 次`
                  : "，★ 而引擎日志（tools.jsonl）里 0 行 ⇒ 监测台完全看不见这一步")
            + (toolsWin && toolsWin.truncated
                ? `（⚠ 两个来源本次都被窗口截断：tools.jsonl 只读尾部 ${toolsWin.windowBytes} 字节，`
                  + `chat.jsonl 每只只读尾部 ⇒ 次数是**下界**）`
                : "")
            : (toolsWin && toolsWin.truncated
                ? `tools.jsonl 最近 ${toolsWin.events.length} 条里 find_tools ${findToolsRows.length} 行`
                  + `（★ 文件共 ${toolsWin.sizeBytes} 字节，本次只读了尾部 ${toolsWin.windowBytes} 字节`
                  + " —— 所以这**不是**「整个文件里 0 行」）"
                : `tools.jsonl 里 find_tools ${findToolsRows.length} 行`),
          evidence: ["monitor/tools.jsonl", "companions/<uuid>/chat.jsonl#assistant.tool_calls", "/api/tool-attribution"],
          why: "★ 这条边是「内脑主动去取延迟工具」。真判据是内脑自己对话里的"
            + " assistant.tool_calls（每条带 id/name/arguments），不是 tools.jsonl ——"
            + "后者完全不记 find_tools / load_skill / todowrite / selfcompile_*。"
            + "完整对照见 /api/tool-attribution（含「只是文本里出现过」那一列，"
            + "那是拦截消息贡献的，**不是调用**）。",
          blind: findToolsRows.length === 0,
        }),
      ];

      return {
        ok: true,
        monitorDir,
        configDir,
        timestamp: new Date().toISOString(),
        // ★ 采样上限**逐文件**列出，而不是一个数。理由见 SAMPLING 那段注释：
        //   一刀切的小上限会让「条数就是产品」的节点在文件变大后静默失真。
        //   tools.jsonl 刻意保持小上限，并让那句话自己降级。
        // ★★ 第 5 组红线第四条：这一列格子里有多少是真量出来的。
        //   没有它的话，一列里混着 ? 与 0 会被整列读成「0」。
        //   尤其 `zeros`（真 0）与 `unknown+unobservable`（没量到）必须分开报 ——
        //   「真的没有」与「没去看」是两件事，合成一个数就回到原点了。
        audit: auditColumn(nodes.map((n) => n.measure)),
        sampling: {
          perFile: SAMPLING,
          default: DEFAULT_SAMPLING,
          note: "「0 条」有三种含义，本页每条边都标了属于哪一种；"
            + "「有多少条」也有第三种含义 —— 窗口没覆盖到（看各节点 freshness.window.truncated）",
        },
        // 本次实际读到的窗口事实，逐文件（截断了就是 true）
        windows: {
          "instrumentation.jsonl": winFacts(instrWin),
          "events.jsonl": winFacts(eventsWin),
          "brain.jsonl": winFacts(brainWin),
          "tools.jsonl": winFacts(toolsWin),
        },
        nodes,
        edges,
        // 区 2：内脑门禁时间线。brain.jsonl 每行自带十道闸 + 全部输入。
        brain: {
          source: "monitor/brain.jsonl",
          total: gates.length,
          sample: newestLast(gates, 60).map((r) => ({
            timestamp: r.timestamp, type: r.type,
            companion: r.data?.companion_id || null,
            blocker: r.data?.blocker ?? null,
            blockerLabel: r.data?.blocker_label ?? null,
            dead: r.data?.dead ?? null,
            externallyDriven: r.data?.externally_driven ?? null,
            turnPause: r.data?.turn_pause ?? null,
            awaitingLlm: r.data?.awaiting_llm_response ?? null,
            compacting: r.data?.compacting ?? null,
            toolsBusy: r.data?.tool_calls_outstanding ?? null,
            conversationMsgs: r.data?.conversation_msgs ?? null,
            contextTokens: r.data?.context_tokens ?? null,
            modelWindow: r.data?.model_window ?? null,
            modelWindowKnown: r.data?.model_window_known ?? null,
            autoCompactArmed: r.data?.auto_compact_armed ?? null,
            externalTakeMode: r.data?.external_take_mode ?? null,
            externalTakes: r.data?.external_takes ?? null,
            takenEvents: r.data?.taken_events ?? null,
            problems: r.data?.problems || [],
            raw: r.data,
          })),
        },
        // 任务结果的成不成分布：B10 之后才有判据
        outcomes: {
          source: "monitor/events.jsonl#task_finished",
          total: taskFinished.length,
          withStatus: withStatus.length,
          statusAbsent: taskFinished.length - withStatus.length,
          byStatus,
          sample: newestLast(taskFinished, 20).map((r) => ({
            timestamp: r.timestamp,
            id: r.data?.id ?? null, task: r.data?.task ?? null,
            status: r.data?.status ?? "(无 status：B10 之前的老事件，永不补字段)",
          })),
        },
        // 我今天栽了三次的地方，画在图上而不是留在日志里。
        // ★ 每条 `what` 的措辞都要与本页实际读到的窗口相符：截断的文件不许说「全文件 0 行」，
        //   那是把一个窗口当全量（与本组红线同型）。措辞随 windows 动态生成。
        blindSpots: [
          { id: "tools-jsonl-missing-find-tools",
            what: attributionData === null
              ? "★ 归因读数（/api/tool-attribution）取不到 ⇒ 本页无法判断「内脑到底调没调」"
              : (blindList && blindList.length
                  ? `tools.jsonl 不记 ${blindList.length} 个内脑真调过的工具`
                    + `（${blindList.slice(0, 6).join(", ")}${blindList.length > 6 ? " …" : ""}）`
                    + (findToolsBrainCalls ? `。其中 find_tools 内脑真调用 ${findToolsBrainCalls} 次` : "")
                    + (attributionData.totals.window_sensitive.blind_to_journal
                        ? "　⚠ 且本次两个来源都被窗口截断 ⇒ 这份名单里可能混进了「窗口没覆盖到」的假盲"
                        : "")
                  : "tools.jsonl 覆盖了本次窗口里内脑调过的全部工具"),
            so: "「内脑去取延迟工具」这一步监测台看不见 ⇒ 我曾据此把它判成「它从没去取过工具」，错了三次",
            truth: "真判据是内脑自己对话里的 assistant.tool_calls（每条带 id/name/arguments），"
              + "完整三源对照见 /api/tool-attribution" },
          { id: "no-presentation-receipt-journal", what: "PresentationReceipt 只在内存，不落盘", so: "「召回留痕」与「配对」两环在图上永远是空的", truth: "这两个环的状态只出现在 experience_verify 的返回值里（MCP 侧能读到）" },
          { id: "verify-not-instrumented", what: "verify / retract 完全不埋点", so: "「回报」与「验证状态变化」两环不可观测", truth: "ExperienceMonitor.publish 全仓只有 learned / planning_knowledge 两个调用点" },
          { id: "ai-jsonl-has-no-tool-names", what: "ai.jsonl 的 type=turn 只记 turn/tools/convo_msgs/companion_id", so: "拿它回答「内脑调了什么工具」会得到假零", truth: "工具调用在 tools.jsonl，而它又不记 find_tools ⇒ 内脑的工具面必须读 chat.jsonl" },
          { id: "external-call-no-task-finished", what: "MCP 派的任务结构性不产生 task_finished", so: "不能用 MCP 驱动去验证「结果回流」通了", truth: "CompanionBrain.java:290-292 的 if (rec.isExternalCall()) continue" },
        ],
        store,
        learner: learnerRaw,
      };
    },
  };
}