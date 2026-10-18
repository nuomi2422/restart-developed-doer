// Token 消耗可见面 —— 「她这一局烧了多少 token、烧得多快、上下文还剩多少」。
//
// 为什么要单独一页（2026-10-04）：原版 Numen 有三套 token 读数，监测台**一套都没接**——
//   ① 游戏内 `/usage` → `EntityAgentLoop.usageTotals()`（TokenLedger）
//   ② 会话台账 `config/numen/companions/<uuid>/stats.json`
//   ③ 逐轮 usage `monitor/{rdd,context}*.jsonl` 的 `llm_response.data.{promptTokens,totalTokens}`
// 于是「钱烧在哪」这件事在网页上完全不可见 —— 只能进游戏敲 /usage。
//
// ★★★ 本页最重要的纪律：**这三套口径不能相加，也不能互相替代**。
//
//   〔1〕台账 totalTokens ≠ 逐轮 totalTokens 之和。
//        `TokenLedger.add()` 里 `total += u.fresh()` —— **只累加"新鲜的"那部分，
//        缓存命中不重复计入**（这正是它能被当成"这局真实花掉多少"的原因）。
//        而 `llm_response.totalTokens` 是 provider 报的**这一次请求**的数，缓存命中照样算进去。
//        实机对照（companions/ed1606db，2026-10-04 01:56）：台账 totalTokens=8,124,996，
//        同期 jsonl 里 3870 轮 totalTokens 加起来是同一量级但**逐轮差** —— 两者对不上是**正常的**。
//        ⇒ 本页把两者**分区摆放、各自标注来源**，绝不相加成一个"总消耗"。
//
//   〔2〕逐轮事件里**没有 completionTokens 字段**，只有 promptTokens 与 totalTokens。
//        本页的"输出"= totalTokens - promptTokens，是**推算值**，页面上标了「推算」。
//
//   〔3〕速率的分母用**窗口全长**（含空闲），不是"实际在跑的那几分钟"。
//        否则一段 5 分钟里只跑了 30 秒的窗口会显示出 10 倍的假速率。
//        两个口径都给：tokensPerMin（窗口平均）与 turnsPerMin（轮/分钟），页面并排显示。
//
//   〔4〕`brain.jsonl` 的 `context_tokens` 是「上一轮真实用量」（EntityAgentLoop:1667-1672
//        拿不到 usage 时才退回本地估算），它跟 `model_window` / `auto_compact_threshold`
//        一起才是原版的**自动压缩闸**。brain.jsonl **不在 server.mjs 的 CATEGORIES 里**，
//        所以前端永远看不到它 —— 这是本页必须放在服务端读盘的理由之一。
import { readdir, stat, readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { readJsonlWindow } from "./tail-jsonl.mjs";
import { freshnessOf } from "./freshness.mjs";

/** 每个文件最多读这么多字节尾部。轮转文件单个 16.8MB，全读会拖垮每次请求。 */
const MAX_FILE_BYTES = 4 * 1024 * 1024;
/** 读到这么多轮就停 —— 速率只需要最近的轮次，再往前的历史交给"覆盖时段"说明。 */
const TARGET_TURNS = 600;
/** 每分钟柱最多画多少根。 */
const MAX_BUCKETS = 90;
/** 逐轮明细列表给页面多少行。 */
const MAX_TURN_ROWS = 80;

/** 速率窗口。id 给前端当锚点，label 直接显示。 */
const WINDOWS = [
  { id: "5m", label: "近 5 分钟", seconds: 300 },
  { id: "30m", label: "近 30 分钟", seconds: 1800 },
  { id: "2h", label: "近 2 小时", seconds: 7200 },
  { id: "24h", label: "近 24 小时", seconds: 86400 },
  { id: "all", label: "全部已采集", seconds: null },
];

function num(v) {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function isoOrNull(t) {
  return Number.isFinite(t) ? new Date(t).toISOString() : null;
}

function parseTs(s) {
  const t = Date.parse(s);
  return Number.isFinite(t) ? t : null;
}

/** 取整到分钟，用于每分钟柱的 key。 */
function minuteKey(t) {
  return new Date(Math.floor(t / 60000) * 60000).toISOString();
}

function emptyCounts() {
  return { turns: 0, prompt: 0, output: 0, total: 0 };
}

function addCounts(acc, turn) {
  acc.turns += 1;
  acc.prompt += turn.promptTokens;
  acc.output += turn.outputTokens;
  acc.total += turn.totalTokens;
}

function oneDec(n) {
  return n == null ? null : Math.round(n * 10) / 10;
}

export function createTokenView(MONITOR_DIR) {
  // 台账在 <config>/numen/companions/<uuid>/stats.json，而 monitor/ 是它的子目录
  const CONFIG_DIR = resolve(MONITOR_DIR, "..");

  /**
   * 找出所有可能装着 llm_response 的文件，按 mtime 从新到旧排。
   *
   * ★ 为什么要扫轮转文件：实测 `context-*.jsonl` 有 21 个（每个 16.8MB），
   *   而单次 `readJsonlWindow` 只读一个文件的尾部。只读 `context.jsonl` 的话，
   *   一天前的消耗就全没了 —— 而"速率"恰恰要看跨小时的走势。
   */
  async function usageFiles() {
    let names = [];
    try {
      names = await readdir(MONITOR_DIR);
    } catch {
      return [];
    }
    const picked = names.filter((n) => /^(rdd|context)(-.*)?\.jsonl$/.test(n));
    const withMtime = await Promise.all(picked.map(async (name) => {
      try {
        const s = await stat(join(MONITOR_DIR, name));
        return { name, mtimeMs: s.mtimeMs, sizeBytes: s.sizeBytes };
      } catch {
        return null;
      }
    }));
    return withMtime.filter(Boolean).sort((a, b) => b.mtimeMs - a.mtimeMs);
  }

  /**
   * 从新到旧读文件，凑够 TARGET_TURNS 轮就停。
   * 返回 { turns, scanned, skipped } —— skipped 是**故意没读**的文件数，页面必须说出来，
   * 否则「速率」看起来覆盖了全部历史，其实只是最近那几份。
   */
  async function readTurns() {
    const files = await usageFiles();
    const turns = [];
    const scanned = [];
    for (const f of files) {
      if (turns.length >= TARGET_TURNS) break;
      let win = null;
      try {
        win = await readJsonlWindow(join(MONITOR_DIR, f.name), MAX_FILE_BYTES, TARGET_TURNS);
      } catch {
        // 读不到就跳过这一个文件，不让一个坏轮转毁掉整页
        continue;
      }
      scanned.push({ file: f.name, sizeBytes: f.sizeBytes, turnsInFile: 0 });
      for (const e of win.events) {
        if (e.type !== "llm_response") continue;
        const d = e.data || {};
        const promptTokens = num(d.promptTokens);
        const totalTokens = num(d.totalTokens);
        const ts = parseTs(e.timestamp);
        // 没有 usage 的轮次不能当 0 —— 那会让速率凭空少一截却看不出来
        if (promptTokens == null || totalTokens == null || ts == null) continue;
        const resp = d.response || {};
        turns.push({
          at: e.timestamp,
          ts,
          actor: d.actor || "?",
          companionId: d.companionId || d.companion_id || "",
          model: d.model || "?",
          phase: d.phase || "",
          requestId: d.requestId || "",
          promptTokens,
          totalTokens,
          // ★ 推算：事件里没有 completionTokens 这个字段（见文件头〔2〕）
          outputTokens: Math.max(0, totalTokens - promptTokens),
          finishReason: resp.finish_reason || "",
          derivedOutput: true,
        });
        scanned[scanned.length - 1].turnsInFile += 1;
      }
    }
    turns.sort((a, b) => a.ts - b.ts);
    const scannedNames = new Set(scanned.map((s) => s.file));
    const skipped = files.filter((f) => !scannedNames.has(f.name)).map((f) => f.name);
    return { turns, scanned, skipped, totalFiles: files.length };
  }

  /** 速率：每个时间窗一轮，各自的 token/分钟与轮/分钟。 */
  function rateOf(turns, nowMs) {
    return WINDOWS.map((w) => {
      const from = w.seconds == null ? null : nowMs - w.seconds * 1000;
      const picked = from == null ? turns : turns.filter((t) => t.ts >= from);
      const c = emptyCounts();
      for (const t of picked) addCounts(c, t);
      // 分母：定长窗口用窗口全长；「全部已采集」用实际采集跨度，下限 1 分钟免得除出天文数字
      const spanMs = w.seconds != null
        ? w.seconds * 1000
        : Math.max(60000, (turns.length ? turns[turns.length - 1].ts : nowMs) - (turns.length ? turns[0].ts : nowMs));
      const minutes = spanMs / 60000;
      return {
        id: w.id,
        label: w.label,
        seconds: w.seconds,
        turns: c.turns,
        prompt: c.prompt,
        output: c.output,
        total: c.total,
        tokensPerMin: oneDec(c.total / minutes),
        promptPerMin: oneDec(c.prompt / minutes),
        outputPerMin: oneDec(c.output / minutes),
        turnsPerMin: Math.round((c.turns / minutes) * 100) / 100,
        spanMinutes: oneDec(minutes),
        idle: picked.length === 0,
      };
    });
  }

  /** 每分钟一根柱，给页面画走势。 */
  function bucketsOf(turns, nowMs) {
    const map = new Map();
    for (const t of turns) {
      const k = minuteKey(t.ts);
      let b = map.get(k);
      if (!b) {
        b = { minute: k, ts: Date.parse(k), turns: 0, prompt: 0, output: 0, total: 0 };
        map.set(k, b);
      }
      addCounts(b, t);
    }
    let list = [...map.values()].sort((a, b) => a.ts - b.ts);
    // 只留最近 MAX_BUCKETS 根；中间的空分钟不补 —— 补了会让人以为那几分钟在烧 token
    if (list.length > MAX_BUCKETS) list = list.slice(-MAX_BUCKETS);
    const peak = list.reduce((m, b) => Math.max(m, b.total), 0);
    return { buckets: list, peak, truncatedMinutes: Math.max(0, [...map.keys()].length - MAX_BUCKETS) };
  }

  /** 按 actor / model 分组，回答"是谁在烧"。 */
  function groupBy(turns, key) {
    const map = new Map();
    for (const t of turns) {
      const k = t[key] || "?";
      let g = map.get(k);
      if (!g) { g = { key: k, ...emptyCounts(), models: {} }; map.set(k, g); }
      addCounts(g, t);
      g.models[t.model] = (g.models[t.model] || 0) + 1;
    }
    return [...map.values()].sort((a, b) => b.total - a.total);
  }

  /**
   * 会话台账（游戏内 /usage 读的是同一份文件）。
   *
   * ★ 这里是**累计值**，没有时间序列 —— 所以它给不出速率，只能给"到此刻为止一共花了多少"。
   *   要速率只能靠采样两次做差，而服务端不常驻，所以本页不给"台账速率"，
   *   只给"台账快照 + 文件最后写入时间"，让页面能说清它有多旧。
   */
  async function ledger() {
    const dir = join(CONFIG_DIR, "companions");
    let ids = [];
    try {
      ids = await readdir(dir);
    } catch {
      return { readable: false, companions: [], note_zh: "读不到 companions 目录，台账这一块没有数据。" };
    }
    const out = [];
    for (const id of ids) {
      const file = join(dir, id, "stats.json");
      let raw = null;
      let mtimeMs = null;
      try {
        raw = await readFile(file, "utf8");
        mtimeMs = (await stat(file)).mtimeMs;
      } catch {
        continue;
      }
      let o = null;
      try { o = JSON.parse(raw); } catch { o = null; }
      if (!o) {
        out.push({ id, file, unreadable: true, mtime: isoOrNull(mtimeMs) });
        continue;
      }
      const input = Math.max(0, Number(o.input) || 0);
      const output = Math.max(0, Number(o.output) || 0);
      const cacheRead = Math.max(0, Number(o.cacheRead) || 0);
      const cacheWrite = Math.max(0, Number(o.cacheWrite) || 0);
      // 缓存命中率的分母：缓存读 + 未命中的新输入（含缓存写）。跟 UsageCommand 的口径一致。
      const denom = cacheRead + input + cacheWrite;
      out.push({
        id,
        file,
        unreadable: false,
        totalTokens: Math.max(0, Number(o.totalTokens) || 0),
        input,
        output,
        cacheRead,
        cacheWrite,
        cacheHitRate: denom > 0 ? Math.round((cacheRead / denom) * 1000) / 10 : null,
        mtime: isoOrNull(mtimeMs),
        secondsSinceWrite: mtimeMs == null ? null : Math.round((Date.now() - mtimeMs) / 1000),
      });
    }
    out.sort((a, b) => (b.mtime || "") > (a.mtime || "") ? 1 : -1);
    return {
      readable: out.length > 0,
      companions: out,
      note_zh: "台账 = config/numen/companions/<uuid>/stats.json，游戏内 /usage 读的是同一份。"
          + "totalTokens 只累加 fresh（缓存命中不重复计），所以它跟上面逐轮 totalTokens 之和不是同一口径，不能相加。",
    };
  }

  /**
   * 上下文水位（自动压缩闸）。数据在 brain.jsonl 的 gate 事件里，
   * 而 brain.jsonl 不在 server.mjs 的 CATEGORIES 中 —— 前端拿不到，只能服务端读。
   */
  async function contextWindow() {
    const file = "brain.jsonl";
    let win = null;
    try {
      win = await readJsonlWindow(join(MONITOR_DIR, file), MAX_FILE_BYTES, 2000);
    } catch {
      return {
        readable: false,
        note_zh: "读不到 brain.jsonl，上下文水位没有数据。",
      };
    }
    const gates = (win.events || []).filter((e) => e.type === "gate" && e.data);
    if (!gates.length) {
      return { readable: true, has_records: false, note_zh: "brain.jsonl 里还没有 gate 事件。" };
    }
    // 每只同伴取最后一条
    const latest = new Map();
    for (const g of gates) {
      const id = g.data.companion_id || "?";
      latest.set(id, g);
    }
    const companions = [...latest.values()].map((g) => {
      const d = g.data;
      const used = num(d.context_tokens);
      const win2 = num(d.model_window);
      const threshold = num(d.auto_compact_threshold);
      const known = d.model_window_known === true;
      return {
        id: d.companion_id || "?",
        at: g.timestamp,
        contextTokens: used,
        modelWindow: win2,
        modelWindowKnown: known,
        autoCompactThreshold: threshold,
        // 水位百分比：有窗口且已知才算，否则 null（不拿 0 冒充"没占"）
        waterPercent: used != null && win2 ? Math.round((used * 1000) / win2) / 10 : null,
        // 离压缩闸还剩多少 token；已经越线就是负数
        headroomTokens: used != null && threshold != null ? threshold - used : null,
        compacting: d.compacting === true,
        conversationMsgs: num(d.conversation_msgs),
        blocker: d.blocker || "",
        blockerLabel: d.blocker_label || "",
      };
    }).sort((a, b) => (b.at || "") > (a.at || "") ? 1 : -1);
    return {
      readable: true,
      has_records: true,
      companions,
      note_zh: "context_tokens 是上一轮的真实用量（拿不到 usage 时才退回本地估算），"
          + "与 model_window / auto_compact_threshold 一起构成原版的自动压缩闸。",
    };
  }

  return {
    async all() {
      const nowMs = Date.now();
      const { turns, scanned, skipped, totalFiles } = await readTurns();
      const bucketData = bucketsOf(turns, nowMs);
      const newestFile = scanned[0] ? scanned[0].file : "rdd.jsonl";
      // freshness 用最新那一份算，这样「这一页停更多久」有个统一口径
      let fresh = null;
      try {
        let win = null;
        try {
          win = await readJsonlWindow(join(MONITOR_DIR, newestFile), MAX_FILE_BYTES, 50);
        } catch { win = null; }
        fresh = await freshnessOf({ monitorDir: MONITOR_DIR, fileName: newestFile, window: win, nowMs });
      } catch {
        fresh = null;
      }

      const reasons = [];
      if (!turns.length) {
        reasons.push("没有采集到任何带 usage 的 llm_response —— 可能游戏没在跑，或上游 provider 没回 usage 帧。");
      }

      return {
        ok: true,
        generatedAt: isoOrNull(nowMs),
        // ★ 这一页的数字覆盖哪一段、停更多久、是不是当前这一局 —— 第 5 组红线「历史 ≠ 当前」
        coverage: {
          turnsRead: turns.length,
          firstTurnAt: turns.length ? turns[0].at : null,
          lastTurnAt: turns.length ? turns[turns.length - 1].at : null,
          filesScanned: scanned,
          filesSkipped: skipped,
          totalCandidateFiles: totalFiles,
          note_zh: skipped.length
            ? ("为了不拖垮每次请求，只读了最新的 " + scanned.length + " / " + totalFiles
                + " 份文件（每份只读尾部）；更早的历史没进来，速率窗口因此只反映这段时间。")
            : ("读了全部 " + totalFiles + " 份候选文件（每份只读尾部）。"),
        },
        freshness: fresh,
        rate: rateOf(turns, nowMs),
        buckets: bucketData,
        byActor: groupBy(turns, "actor"),
        byModel: groupBy(turns, "model"),
        turns: turns.slice(-MAX_TURN_ROWS).reverse(),
        ledger: await ledger(),
        contextWindow: await contextWindow(),
        caveats: [
          {
            id: "two-ledgers-not-addable",
            note_zh: "台账 totalTokens 与逐轮 totalTokens 之和不是同一个数：台账只累加 fresh，"
                + "缓存命中不重复计。两块分区摆放，不要相加。",
          },
          {
            id: "output-is-derived",
            note_zh: "逐轮事件里没有 completionTokens 字段，本页的「输出」= totalTokens - promptTokens，是推算值。",
          },
          {
            id: "rate-denominator-is-window",
            note_zh: "速率的分母是窗口全长（含空闲）。想看「真在跑时有多快」，看同时给出的 turnsPerMin 与每分钟柱。",
          },
        ],
        emptyReasons: reasons,
      };
    },
  };
}