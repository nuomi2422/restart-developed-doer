import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { readFileSync } from "node:fs";

const HERE = resolve(fileURLToPath(new URL(".", import.meta.url)));
const PROJECT = resolve(HERE, "..");
const HOST = process.env.RDD_MONITOR_HOST || "127.0.0.1";
const PORT = Number.parseInt(process.env.RDD_MONITOR_PORT || "8776", 10);
const MONITOR_DIR = resolve(process.env.NUMEN_MONITOR_DIR || process.argv[2] || join(PROJECT, "runtime", "numen-monitor"));
const MAX_FILE_BYTES = 4 * 1024 * 1024;
const MAX_EVENTS = 500;
const MIME = { ".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".json": "application/json; charset=utf-8", ".md": "text/markdown; charset=utf-8", ".woff2": "font/woff2", ".ttf": "font/ttf", ".otf": "font/otf" };
const CATEGORIES = new Set(["events", "state", "tools", "ai", "context", "ac", "environment", "health", "commands", "rdd", "expmem", "selfcompile"]);

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
    const info = await stat(file);
    if (info.size > MAX_FILE_BYTES) return [{ category: "health", type: "source_oversize", data: { category, bytes: info.size } }];
    const text = await readFile(file, "utf8");
    const lines = text.split(/\r?\n/).filter(Boolean).slice(-MAX_EVENTS);
    return lines.map((line) => { try { return JSON.parse(line); } catch { return { category: "health", type: "invalid_jsonl", data: { category } }; } });
  } catch { return []; }
}
async function snapshot() {
  const result = {};
  let total = 0;
  for (const category of CATEGORIES) {
    result[category] = await readCategory(category);
    total += result[category].length;
  }
  return { ok: true, source: "numen-jsonl", monitorDir: MONITOR_DIR, total, categories: result, timestamp: new Date().toISOString() };
}

// ── Numen AI 决策日志解析（latest.log, GBK）────────────────────────────
// 让监测台看到 AI 怎么思考 / 做了什么 / 上下文是什么。
// 从最新日志（从后往前最多 2000 行）解析 numen-* 决策行。
const NumenLogParser = (() => {
  const LOG_PATH = resolve(process.env.NUMEN_LOG_PATH ||
    "E:\\.minecraft\\versions\\The Best of Twilight Forest\\logs\\latest.log");
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
  if (request.method !== "GET") return json(response, 405, { ok: false, error: "GET only" });
  if (url.pathname === "/api/snapshot") return json(response, 200, await snapshot());
  if (url.pathname === "/api/health") {
    const data = await snapshot();
    return json(response, 200, { ok: true, source: data.source, monitorDir: MONITOR_DIR, total: data.total, lastReadAt: data.timestamp });
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
  // 通用文档：?name=INTRO|ARCHITECTURE|CONTRACT|INTERFACES|REGRESSION|HANDOFF（防路径穿越）
  if (url.pathname === "/api/doc") {
    const name = (url.searchParams.get("name") || "INTRO").replace(/[^A-Za-z0-9_-]/g, "");
    try {
      const doc = await readFile(join(HERE, name + ".md"), "utf8");
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
