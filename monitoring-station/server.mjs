import { createServer } from "node:http";
import { readFile, stat, writeFile, unlink } from "node:fs/promises";
import { dirname, extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { readFileSync } from "node:fs";
import { readJsonlTail } from "./tail-jsonl.mjs";
import { createAcxView } from "./acx-view.mjs";
import { createRddJournal, observationStatus } from "./rdd-journal.mjs";
import { createToolCatalog } from './numen-catalog.mjs';
import { createChainView } from './chain-view.mjs';
import { createExperienceChainView } from './experience-chain-view.mjs';
import { createExperienceLibraryView } from './experience-library-view.mjs';
import { createToolAttributionView } from './tool-attribution.mjs';
import { createTokenView } from './token-view.mjs';
import { createExperimentStatsView } from './experiment-stats.mjs';
import { createWorldFactsView } from './world-facts.mjs';
import { createEconomyView } from './economy.mjs';
import { createSemanticsView } from "./semantics.mjs";

const HERE = resolve(fileURLToPath(new URL(".", import.meta.url)));
const PROJECT = resolve(HERE, "..");
const HOST = process.env.RDD_MONITOR_HOST || "127.0.0.1";
const PORT = Number.parseInt(process.env.RDD_MONITOR_PORT || "8776", 10);
const MONITOR_DIR = resolve(process.env.NUMEN_MONITOR_DIR || process.argv[2] || "D:/rdd/mc/The Best of Twilight Forest/config/numen/monitor");
const MAX_FILE_BYTES = 4 * 1024 * 1024;
const MAX_EVENTS = 500;
const MIME = { ".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".json": "application/json; charset=utf-8", ".md": "text/markdown; charset=utf-8", ".woff2": "font/woff2", ".ttf": "font/ttf", ".otf": "font/otf" };
// 2026-10-01 新增 learner：readCategory 会直接读 <MONITOR_DIR>/learner.jsonl（该文件已由
// plugins/learner 写出，事件类型 noted / carrier_assessment / reviewed）。
// 目的：把「学习者与携带器」这批**脑内改动**变成肉眼可见 —— 否则判据全是内部 jsonl，
// 用户在游戏里完全看不出任何变化（这正是加这一页的起因）。
const CATEGORIES = new Set(["events", "state", "tools", "ai", "context", "ac", "environment", "health", "commands", "rdd", "expmem", "selfcompile", "instrumentation", "learner", "acx", "settlement"]);
const rddJournal = createRddJournal(join(MONITOR_DIR, 'rdd.jsonl'));
const contextJournal = createRddJournal(join(MONITOR_DIR, 'context.jsonl'));
const toolCatalog = createToolCatalog(join(MONITOR_DIR, '..', 'mcp_server.json'));
// 2026-10-02 新增「经验链可见面」：死因/规划知识/注入证据/经验库 + 9 条断点。
// 走独立路由，不进 CATEGORIES、不改 snapshot() —— 避免拖慢既有页，也避免"看起来成立"的假象。
// configDir 取 MONITOR_DIR 的父目录（经验库落在 <gamedir>/config/numen/ 而非 monitor/ 子目录）。
const chainView = createChainView(MONITOR_DIR);
const experienceLibraryView = createExperienceLibraryView(resolve(MONITOR_DIR, '..'));
// 「被提及 ≠ 被调用」需要同时读 monitor/tools.jsonl 与 config/numen/companions/*/chat.jsonl，
// 所以两个目录都要（第二个参数是 configDir，不是 monitorDir —— 拿错的话它会找不到同伴）。
const toolAttributionView = createToolAttributionView(MONITOR_DIR, resolve(MONITOR_DIR, '..'));
// ACX（AC 执行层）状态机可见面：把 acx.jsonl 归并成「一条条运行 + 每步状态」。
const acxView = createAcxView(MONITOR_DIR);
// 2026-10-03 B18「经验链 · 数据流」：把散在六个文件里的读数**连成一条拓扑**。
// 与 chainView 的区别不是「多几个数」，而是回答了一个 chainView 结构上答不了的问题：
// 那六张卡可以每张都好看，而链依然一次都没被走过 —— 单看指标永远看不出来。
const experienceChainView = createExperienceChainView(MONITOR_DIR);
// 2026-10-04 新增「Token 消耗」：原版有三套 token 读数（游戏内 /usage、companions/*/stats.json
// 台账、monitor/*.jsonl 逐轮 usage），网页上一套都没有。必须走服务端读盘，因为
//   ① brain.jsonl（上下文水位 / 自动压缩闸）**不在 CATEGORIES 里**，前端拿不到；
//   ② 速率要看跨小时的走势，而浏览器侧每页只保留 500 条、server 的 MAX_EVENTS 也是 500。
// 三套口径**不可相加**（台账 totalTokens 只累加 fresh），拆开摆在页面上，见 token-view.mjs 文件头。
const tokenView = createTokenView(MONITOR_DIR);
// 2026-10-05 新增「实验统计」：把**每只 AI 在每个目标下**的死亡 / AC 调用 /
// 行动次数并排。必须服务端读盘，因为 ① 要读 9 个日志文件（浏览器一次只拿
// 500 条 × 一类，凑不出跨文件的统计）② 目标时间轴要跨小时对齐（rdd 每 5 秒
// 一条快照）③ AC 事件的归属靠时间线推断，前端拿不到别的文件的同伴信息。
// 自带 12 秒缓存（实测一次 ~380ms / 35KB，页面 12 秒轮一次）。
const experimentStatsView = createExperimentStatsView(MONITOR_DIR);
// 2026-10-05 追加两个**骨架页**（第三批 T3-01 / T3-07 的可见面）。
// 为什么要它们：架构里有这两块，但监测台一个数字都看不到 ——
//   · 「世界是什么样」散在 config/numen/rdd-{assets,facts,history,ledger}/<uuid>.json 四个文件里；
//   · 「能不能关模块 / 限经验注入 / 禁目标」连配置文件都还没有。
// 骨架页的形状：**接上了什么给真读数，没接什么逐条写清「现在拿什么代替 / 怎么自测」**，
// 而且「已接入/未接入」是**运行时探测**出来的 —— 后来的 AI 把数据接上，页面自动翻绿。
const worldFactsView = createWorldFactsView(MONITOR_DIR);
const economyView = createEconomyView(MONITOR_DIR);
// P0-C（81 号 §6）：语义资产只读窗口。configDir = <gamedir>/config/numen（与经验库同一层）。
const semanticsView = createSemanticsView(resolve(MONITOR_DIR, '..'));

function headers(type) {
  return { "Content-Type": type, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Access-Control-Allow-Origin": "http://127.0.0.1:" + PORT };
}
function json(response, status, value) {
  response.writeHead(status, headers("application/json; charset=utf-8"));
  response.end(JSON.stringify(value));
}
async function readCategory(category) {
  if (!CATEGORIES.has(category)) return [];
  const file = join(MONITOR_DIR, category + ".jsonl");
  try {
    return await readJsonlTail(file, MAX_FILE_BYTES, MAX_EVENTS);
  } catch { return []; }
}
// ── RDD 空转止血开关：监测台把监督暂停/恢复写进游戏端 flag 文件 ─────────
// NUMEN_MONITOR_DIR = <游戏>/config/numen/monitor → flag = 上级 config/numen/rdd-supervision.flag
const SUPERVISION_FLAG = resolve(join(MONITOR_DIR, "..", "rdd-supervision.flag"));
async function rddCommand(action) {
  const flag = SUPERVISION_FLAG;
  if (action === "pause") {
    await writeFile(flag, "pause", "utf8");
    return { ok: true, state: "paused", flag, note: "监督已暂停：Detector 不再 nudge/自动重试/判失败，资产检测推进照常" };
  }
  if (action === "resume") {
    await unlink(flag).catch(() => {});
    return { ok: true, state: "running", flag, note: "监督已恢复：检测心跳将自动重新放行拍醒" };
  }
  // status（默认）
  let state = "running";
  try { const content = (await readFile(flag, "utf8")).trim(); if (content && content !== "run") state = "paused"; } catch { /* 无文件 = 运行中 */ }
  return { ok: true, state, flag };
}
function readBody(request) {
  return new Promise((resolveBody, rejectBody) => {
    let data = "";
    request.on("data", (chunk) => { data += chunk; if (data.length > 1e6) request.destroy(new Error("body too large")); });
    request.on("end", () => resolveBody(data));
    request.on("error", rejectBody);
  });
}

// ── payload 兼容映射（2026-10-01）────────────────────────────────
// 前端 monitor-store.js / rdd-view.js 共 27 处读 `event.payload`，
// 而 /api/snapshot 返回的是 jsonl 原样（字段叫 `data`）—— 于是
// `rdd-view.js:63 var p = e.payload || {}` 恒为 {}，
// `if (!snapshot && p.taskChain)` 恒 false →
// 页面永远显示「等待任务链观测」，即使 taskchain_snapshot 就在返回里。
//
// 修法：**补**一个 payload 别名，保留 data 不动。
//   · SSE 通路（numen-adapter.js 读 event.data）完全不受影响
//   · 前端 27 处 .payload 一次性全部兼容，不用改任何前端文件
// 只在缺 payload 时补，不覆盖已有值。
function withPayload(e) {
  if (e && typeof e === "object" && e.data && !e.payload) {
    return { ...e, payload: e.data };
  }
  return e;
}

async function snapshot() {
  const result = {};
  let total = 0;
  for (const category of CATEGORIES) {
    if (category === 'rdd' || category === 'context') continue;
    result[category] = (await readCategory(category)).map(withPayload);
    total += result[category].length;
  }
  const rdd = await rddJournal.read();
  const context = await contextJournal.read();
  result.context = context.events.map(withPayload);
  total += context.events.length;
  result.rdd = rdd.events.map(withPayload);
  total += rdd.events.length;
  return { ok: true, source: "numen-jsonl", monitorDir: MONITOR_DIR, total, categories: result, timestamp: new Date().toISOString(), contextJournal: { readable: context.ok, error: context.error, invalidLines: context.invalid }, rddJournal: { readable: rdd.ok, error: rdd.error, invalidLines: rdd.invalid }, observation: observationStatus(rdd.events) };
}

// ── Numen AI 决策日志解析（latest.log, GBK）────────────────────────────
// 让监测台看到 AI 怎么思考 / 做了什么 / 上下文是什么。
// 从最新日志（从后往前最多 2000 行）解析 numen-* 决策行。
const NumenLogParser = (() => {
  const LOG_PATH = resolve(process.env.NUMEN_LOG_PATH ||
    "D:\\rdd\\mc\\The Best of Twilight Forest\\logs\\latest.log");
  const MAX_LINES = 2000;
  const MAX_RETURN = 300;

  function decodeGbk(buf) {
    try { return new TextDecoder("gbk").decode(buf); }
    catch { return buf.toString("utf8"); }
  }

  function parseLine(line) {
    // [318月2026 00:47:29.584] [Render thread/INFO] [Numen/]: [numen-xxx] payload
    const m = line.match(/\[(\d+)月(\d{4}) (\d{2}:\d{2}:\d{2}\.\d+)\].*?\[Numen\/\]: \[(numen-[^\]]+)\](.*)$/);
    if (!m) return null;
    const [, month, year, time, tag, payload] = m;
    const ts = `${year}-${month.padStart(2, "0")}-01T${time}Z`;
    const catTag = tag;
    let text = payload.trim();

    // 分类映射 + 提取干净文本
    let category = "ai";
    let type = "log";
    if (catTag.startsWith("numen-dispatch")) {
      category = "tools"; type = "tool_call";
      const m2 = text.match(/dispatch tool=(\S+)/);
      if (m2) text = "调用工具: " + m2[1];
    }
    else if (catTag.startsWith("numen-entity")) {
      if (/assistant \(final\)/.test(text)) {
        category = "ai"; type = "assistant";
        const m2 = text.match(/assistant \(final\): (.*)$/);
        if (m2) text = m2[1].trim();
      }
      else if (/user prompt/.test(text)) {
        category = "context"; type = "user_prompt";
        const m2 = text.match(/user prompt \(\d+ chars\)[^:]*: (.*)$/);
        if (m2) text = m2[1].trim();
      }
      else if (/turn \d+:/.test(text)) {
        category = "context"; type = "turn";
        const m2 = text.match(/turn \d+: convo=(\d+) msgs, tools=(\d+)/);
        if (m2) text = "AI 回合开始 · 会话 " + m2[1] + " 条 · 工具 " + m2[2] + " 个";
      }
      else if (/queued event/.test(text)) { category = "events"; type = "event"; }
      else { category = "ai"; type = "loop"; }
    }
    else if (catTag.startsWith("numen-llm")) {
      category = "ai"; type = "llm";
      const m2 = text.match(/chat done in \d+ms.*?finish=(\w+)/);
      if (m2) text = "LLM 回合结束: " + m2[1];
    }
    else if (catTag.startsWith("numen-inv")) {
      category = "context"; type = "inventory";
      const m2 = text.match(/背包[^:：]*:(\d+) 字符/);
      if (m2) text = "背包上下文 " + m2[1] + " 字符";
    }
    else if (catTag.startsWith("numen-ctx")) {
      category = "context"; type = "runtime_state";
      const m2 = text.match(/runtime_state → (\S+)/);
      if (m2) text = "运行时状态: " + m2[1];
    }
    else if (catTag.startsWith("numen-state")) { category = "state"; type = "companion_state"; }
    else if (catTag.startsWith("numen-queue")) { category = "ai"; type = "queue"; }

    return { category, type, tag: catTag, time: time, text, raw: line.slice(0, 400) };
  }

  function read() {
    try {
      const buf = readFileSync(LOG_PATH);
      const text = decodeGbk(buf);
      const lines = text.split(/\r?\n/).filter((l) => l.trim());
      const tail = lines.slice(-MAX_LINES);
      const events = [];
      for (const line of tail) {
        const ev = parseLine(line);
        if (ev) events.push(ev);
      }
      const ai = events.filter((e) => e.category === "ai").slice(-MAX_RETURN);
      const context = events.filter((e) => e.category === "context").slice(-MAX_RETURN);
      const tools = events.filter((e) => e.category === "tools").slice(-MAX_RETURN);
      return { ok: true, source: "numen-latest.log", logPath: LOG_PATH, ai, context, tools, total: events.length };
    } catch (e) {
      return { ok: false, error: String(e.message), source: "numen-latest.log" };
    }
  }

  return { read };
})();

const server = createServer(async (request, response) => {
  const url = new URL(request.url, "http://" + HOST + ":" + PORT);
  // RDD 空转止血开关：GET ?action=pause|resume|status 或 POST {action}（会写游戏端 flag 文件）
  if (url.pathname === "/api/rdd-command") {
    let action = url.searchParams.get("action") || "";
    if (request.method === "POST" && !action) {
      try { const body = JSON.parse(await readBody(request)); action = body.action || ""; }
      catch { action = ""; }
    }
    try { return json(response, 200, await rddCommand(action)); }
    catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
  }
  if (request.method !== "GET") return json(response, 405, { ok: false, error: "GET only" });
  if (url.pathname === "/api/snapshot") return json(response, 200, await snapshot());
  // ── 经验链可见面（2026-10-02）────────────────────────────────────────
  // 一次给全：用户要看的是"整条链"，拆成 6 个请求反而看不清哪段断了。
  if (url.pathname === "/api/chain") {
    try { return json(response, 200, await chainView.all()); }
    catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
  }
if (url.pathname === "/api/chain-gaps") {
  try { return json(response, 200, { ok: true, gaps: await chainView.gaps() }); }
  catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
}
if (url.pathname === "/api/experience-chain") {
  try { return json(response, 200, await experienceChainView.all()); }
  catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
}
if (url.pathname === "/api/experience-library") {
  try { return json(response, 200, await experienceLibraryView.read()); }
  catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
}
// ★「被提及 ≠ 被调用」：并排放引擎日志 / 内脑自己的 tool_calls / 只是文本里出现过。
//   与其它只读口一样零副作用；自带 8 秒缓存（页面每 5 秒轮询一次，
//   而这两个文件加起来十几 MB，不缓存就是纯浪费）。
if (url.pathname === "/api/tool-attribution") {
  try { return json(response, 200, await toolAttributionView.all()); }
  catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
}
if (url.pathname === "/api/acx") {
    try { return json(response, 200, await acxView.all()); }
    catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
  }
  if (url.pathname === "/api/token-rate") {
    try { return json(response, 200, await tokenView.all()); }
    catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
  }
  // 实验统计（每个 AI × 每个目标）。一次给全：页面上的交叉表 / 曲线 / 排行榜
  // 都是同一份聚合的不同切面，拆成多个请求反而会出现「表和曲线对不上」。
  if (url.pathname === "/api/experiment-stats") {
    try { return json(response, 200, await experimentStatsView.all()); }
    catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
  }
  // 单一世界 · 环境事实（骨架页）：扫 config/numen/rdd-{assets,facts,history,ledger}/
  if (url.pathname === "/api/world-facts") {
    try { return json(response, 200, await worldFactsView.all()); }
    catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
  }
  // 经济与开关（骨架页）：acx 库 + 磁盘脚本 + 落盘记录 + 运行事件流
  if (url.pathname === "/api/economy") {
    try { return json(response, 200, await economyView.all()); }
    catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
  }
  // 语义资产（P0-C）：只读 <config/numen>/rdd-semantics/<uuid>.json。
  // 四态分开（NO_DIRECTORY / NO_FILES / CORRUPT / PARTIAL / OK），不把「没派生」显示成「0 条」。
  if (url.pathname === "/api/rdd-semantics") {
    try { return json(response, 200, await semanticsView.all()); }
    catch (e) { return json(response, 500, { ok: false, error: String(e.message) }); }
  }
  if (url.pathname === '/api/tool-catalog') return json(response, 200, await toolCatalog.read());
  if (url.pathname === "/api/health") {
    const data = await snapshot();
    return json(response, 200, { ok: true, source: data.source, monitorDir: MONITOR_DIR, total: data.total, lastReadAt: data.timestamp, rddJournal: data.rddJournal, observation: data.observation });
  }
  if (url.pathname === "/api/numen-log") {
    return json(response, 200, NumenLogParser.read());
  }
  if (url.pathname === "/api/architecture") {
    try {
      const arch = await readFile(join(HERE, "ARCHITECTURE.md"), "utf8");
      return json(response, 200, { ok: true, markdown: arch });
    } catch (e) {
      return json(response, 200, { ok: false, error: String(e.message) });
    }
  }
  // 通用文档：?name=INTRO|ARCHITECTURE|CONTRACT|INTERFACES|REGRESSION|HANDOFF|WIRING-worldeco（防路径穿越）
  // 文档名里带连字符的文件（「接线清单-世界与经济-20261005.md」）会被防穿越正则
  //   去掉连字符而读不到，所以给它一个**白名单别名**，别让调用方传真文件名。
  if (url.pathname === "/api/doc") {
    const name = (url.searchParams.get("name") || "INTRO").replace(/[^A-Za-z0-9_-]/g, "");
    const DOC_ALIAS = { "WIRING-worldeco": "接线清单-世界与经济-20261005.md" };
    const fileName = DOC_ALIAS[name] || (name + ".md");
    try {
      const doc = await readFile(name === 'INTRO' ? join(PROJECT, 'docs', 'rdd-eight-modules.md') : join(HERE, fileName), "utf8");
      return json(response, 200, { ok: true, name: name, markdown: doc });
    } catch (e) {
      return json(response, 200, { ok: false, name: name, error: String(e.message) });
    }
  }
  const wanted = url.pathname === "/" ? "/monitoring-station/index.html" : url.pathname;
  const candidates = url.pathname === "/" ? [join(PROJECT, "monitoring-station", "index.html")] : [
    join(PROJECT, "monitoring-station", url.pathname),
    join(PROJECT, url.pathname)
  ];
  const file = candidates.map((candidate) => normalize(candidate)).find((candidate) =>
    candidate.startsWith(PROJECT) && MIME[extname(candidate)]);
  if (!file) return response.writeHead(404).end();
  try {
    const body = await readFile(file);
    response.writeHead(200, headers(MIME[extname(file)]));
    response.end(body);
  } catch { response.writeHead(404).end(); }
});
server.listen(PORT, HOST, () => {
  console.info("RDD/Numen monitor: http://" + HOST + ":" + PORT + "/");
  console.info("Numen JSONL source: " + MONITOR_DIR);
});
