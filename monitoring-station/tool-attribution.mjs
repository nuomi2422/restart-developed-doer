// ============================================================
// 工具归因：★「被提及 ≠ 被调用」（第 5 组红线第三条，2026-10-04）
// ============================================================
//
// 起因是我自己被判错三次：
//   第一次读 ai.jsonl 找工具名 ⇒ 结构上就没有 ⇒ 得出「内脑从没调过 learner_intake」
//   第二次读 tools.jsonl ⇒ 「find_tools 全文件 0 行」⇒ 得出「它从没去取过工具」
//   第三次用 MCP 点名让它试 ⇒ 它立刻调了 ⇒ 前两次的结论全错
//
// 根因不是判断力，是**三个来源各自只覆盖一部分，而它们长得一模一样**：
//
//   A · monitor/tools.jsonl 的 type=tool_call
//     引擎写的。覆盖 MCP 调用 + 内脑调用。**但它完全不记 find_tools / load_skill
//     / todowrite / selfcompile_* 等 11 个工具**（实测 2026-10-04）。
//   B · companions/<uuid>/chat.jsonl 的 role=assistant + tool_calls[].name
//     ★ 内脑自己的对话记录，**这是「内脑调了什么」的真判据**
//     （实测每条都带 id / name / arguments）。
//     但它**只覆盖内脑** —— MCP 调用不进内脑对话。
//   C · 同一文件里 role=tool 的 content 与 assistant 散文里出现的工具名
//     ★ **这只是「字符串出现过」，最容易被当成调用。**
//     拦截消息「工具 mine 的参数定义尚未取回。先调 find_tools("select:mine")…」
//     每拦一次就贡献一次 find_tools —— 而它一次都没被调。
//
// ⇒ 本 view 把三个来源并排放，让「看不见」变成一条读数，而不是一句推测。
//
// ★ 三条不许省的诚实边界（写在文件头，因为它们决定了这个读数能回答什么）：
//
//   1. **两个来源都不完整，各覆盖一半。**
//      「A 有而 B 没有 ⇒ 内脑从没调过它」由 B 证实；
//      但「那一定是 MCP 调的」**不可证**（也可能是 B 的窗口把那段切掉了）。
//      同一句话里两句都要说，只说前半句就是在编。
//
//   2. **窗口截断时两个来源都只是下界。** 真实体量（2026-10-04）：
//      tools.jsonl 11.8MB、本页只读尾部 4MB；chat.jsonl 单只最大 60MB、
//      本页只读每只尾部 CHAT_CAP。所以 blind_to_journal 与 journal_only
//      **必须配窗口读数一起看**，不能单独当结论。
//
//   3. **`mentioned_in_result` 是字符串匹配，不是调用。**
//      它回答「这个名字在结果文本里出现过几次」，不回答「被调了几次」。
//      拿它当调用数，就是我第一次犯的那个错。
//
// 为什么不写进引擎：owner 2026-10-03 冻结了功能代码。
// 「让 tools.jsonl 也记 find_tools」是引擎侧改动（ExecuteToolPayload），属冻结范围。
// 本 view 只把缺口量出来，不修。

import { open, readdir } from "node:fs/promises";
import { join } from "node:path";
import { readJsonlWindow } from "./tail-jsonl.mjs";

// ⚠️ 刻意**不抬到 16MB**：抬了会让「journal_only」这个判断今天恰好成立、
//    一周后悄悄变假 —— 与 sampling 那条同一条纪律。宁可少算，不说满。
const JOURNAL_CAP = 4 * 1024 * 1024;
const JOURNAL_EVENTS = 5000;
// 每只同伴的 chat 尾部上限。最大的一只有 60MB，全读会让每次扫描做 35MB+ 解析。
const CHAT_CAP = 4 * 1024 * 1024;
// 页面每 5 秒轮询一次，而这两个文件加起来十几 MB ⇒ 不缓存就是纯浪费。
const TTL_MS = 8000;

async function readLines(file, cap) {
  let h;
  try { h = await open(file, "r"); } catch { return { lines: [], size: 0, window: null }; }
  try {
    const { size } = await h.stat();
    const n = Math.min(size, cap);
    const buf = Buffer.alloc(n);
    const { bytesRead } = await h.read(buf, 0, n, Math.max(0, size - n));
    let d = buf.subarray(0, bytesRead);
    if (size > n) { const nl = d.indexOf(10); d = nl < 0 ? Buffer.alloc(0) : d.subarray(nl + 1); }
    const lastNl = d.lastIndexOf(10);
    d = lastNl < 0 ? Buffer.alloc(0) : d.subarray(0, lastNl + 1);
    return {
      lines: d.toString("utf8").split(/\r?\n/).filter(Boolean),
      size,
      window: {
        truncated: size > n,
        size_bytes: size, window_bytes: d.length,
        note_zh: size > n
          ? ("共 " + size + " 字节，本次只读尾部 " + d.length + " 字节 ⇒ 下面的次数是**下界**")
          : ("整个文件都在读取范围里"),
      },
    };
  } finally { await h.close(); }
}

const bump = (m, k, n = 1) => m.set(k, (m.get(k) || 0) + n);
const allNames2 = (journal, brain, blocked) => [...new Set([...journal.keys(), ...brain.keys(), ...blocked.keys()])];

/**
 * 拦截消息的固定句式（实测两种）：
 *   「工具 <name> 的参数定义尚未取回。先调 find_tools("select:<name>") 拿到它的完整定义…」
 *   「工具 <name> 不可用。你没有取回它。」
 * ⇒ 从结果文本里能**精确**抠出「内脑试过但没取到定义的工具」。
 *
 * ★ 为什么必须抠它：只被提、从未被调的工具**不会出现在 A 或 B 里**，
 *   而我的表原来是按 A ∪ B 建的 ⇒ **这些工具整行都不在表里**。
 *   也就是说「被提及 ≠ 被调用」这个本 view 的主题，那一类行恰恰被滤掉了。
 *   ——「我最需要看见的那一行，正好被过滤器滤掉了」是同一个病。
 */
const BLOCKED_RE = /工具\s+([a-z_][a-z0-9_]*)\s+(?:不可用|的参数定义尚未取回)/g;
function countBlocked(text, into) {
  if (typeof text !== "string" || !text) { return; }
  BLOCKED_RE.lastIndex = 0;
  let m;
  while ((m = BLOCKED_RE.exec(text)) !== null) { bump(into, m[1]); }
}

/** 字符串出现次数。**不是调用** —— 调用方的字段名必须带 mentioned_ 前缀。 */
function countMentions(text, names, into) {
  if (typeof text !== "string" || !text) { return; }
  for (const n of names) { if (text.indexOf(n) >= 0) { bump(into, n); } }
}

export function createToolAttributionView(monitorDir, configDir) {
  let cache = null;

  async function scan() {
    // ---------- A：引擎日志 ----------
    const jr = await readJsonlWindow(join(monitorDir, "tools.jsonl"), JOURNAL_CAP, JOURNAL_EVENTS);
    const journal = new Map();
    for (const row of jr.events) {
      if (row.type !== "tool_call") { continue; }
      const t = row.tool || (row.data && row.data.tool);
      if (t) { bump(journal, t); }
    }

    // ---------- B：内脑自己的 tool_calls（第一遍：只数调用） ----------
    const companionsDir = join(configDir, "companions");
    let ids = [];
    try {
      const entries = await readdir(companionsDir, { withFileTypes: true });
      ids = entries.filter(d => d.isDirectory()).map(d => d.name);
    } catch { /* 目录读不到 ⇒ 如实报 0 只同伴 */ }

    const brain = new Map();
    const brainByCompanion = new Map();   // tool -> [companionId]
    const perCompanion = [];
    let chatLines = 0, chatBad = 0, chatBytes = 0;

    for (const id of ids) {
      const file = join(companionsDir, id, "chat.jsonl");
      const r = await readLines(file, CHAT_CAP);
      if (!r.lines.length) {
        perCompanion.push({ companion_id: id, bytes: 0, lines_read: 0, window: null, brain_calls: 0, local_tools: [] });
        continue;
      }
      chatBytes += r.size;
      const local = new Map();
      let calls = 0;
      for (const l of r.lines) {
        let o;
        try { o = JSON.parse(l); } catch { chatBad++; continue; }
        chatLines++;
        if (o.role !== "assistant" || !Array.isArray(o.tool_calls)) { continue; }
        for (const tc of o.tool_calls) {
          if (!tc || !tc.name) { continue; }
          bump(brain, tc.name);
          bump(local, tc.name);
          if (!brainByCompanion.has(tc.name)) { brainByCompanion.set(tc.name, []); }
          const arr = brainByCompanion.get(tc.name);
          if (arr.indexOf(id) < 0) { arr.push(id); }
          calls++;
        }
      }
      perCompanion.push({
        companion_id: id, bytes: r.size, lines_read: r.lines.length, window: r.window,
        brain_calls: calls,
        local_tools: [...local.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8),
      });
    }

    // ---------- C：提及（第二遍：此时工具名已齐） ----------
    // 之所以要第二遍：要数「某个名字在文本里出现过几次」，得先知道有哪些名字。
    // 而名字里最重要的一批（只在 chat 里出现的那些 = 监测台看不见的那些）
    // 恰恰是**第一遍读到一半才知道的**。一次读做不到。
    const inResult = new Map();
    const inProse = new Map();
    const blocked = new Map();
    // ★ blocked 先扫（第一遍里就收集）—— 它的名字集合会成为 allNames 的一部分，
    //   否则「只被拦过、从未被调」的工具整行都不在表里（那正是本 view 的主题）。
    for (const c of perCompanion) {
      if (!c.lines_read) { continue; }
      const r = await readLines(join(companionsDir, c.companion_id, "chat.jsonl"), CHAT_CAP);
      for (const l of r.lines) {
        let o; try { o = JSON.parse(l); } catch { continue; }
        if (o.role === "tool") {
          countBlocked(o.content, blocked);
        }
      }
    }
    // 提及计数要等 allNames 齐了才准（含 blocked 贡献的名字），所以放在后面。
    for (const c of perCompanion) {
      if (!c.lines_read) { continue; }
      const r = await readLines(join(companionsDir, c.companion_id, "chat.jsonl"), CHAT_CAP);
      for (const l of r.lines) {
        let o; try { o = JSON.parse(l); } catch { continue; }
        if (o.role === "tool") {
          countMentions(o.content, allNames2(journal, brain, blocked), inResult);
        } else if (o.role === "assistant") {
          countMentions(o.content, allNames2(journal, brain, blocked), inProse);
          if (o.extras && typeof o.extras.reasoning_content === "string") {
            countMentions(o.extras.reasoning_content, allNames2(journal, brain, blocked), inProse);
          }
        }
      }
    }

    const allNames = [...new Set([...journal.keys(), ...brain.keys(), ...blocked.keys()])];
    const blindToJournal = [], journalOnly = [], both = [], mentionOnly = [];
    for (const n of allNames) {
      const j = journal.get(n) || 0, b = brain.get(n) || 0;
      if (b > 0 && j === 0) { blindToJournal.push(n); }
      else if (j > 0 && b === 0) { journalOnly.push(n); }
      else if (j > 0 && b > 0) { both.push(n); }
      else { mentionOnly.push(n); }      // ★ 只被提/被拦过，从未被调 —— 本 view 的主题行
    }

    const tools = allNames
      .sort((a, b) => ((brain.get(b) || 0) + (journal.get(b) || 0) + (blocked.get(b) || 0))
        - ((brain.get(a) || 0) + (journal.get(a) || 0) + (blocked.get(a) || 0)))
      .map(name => {
        const jc = journal.get(name) || 0, bc = brain.get(name) || 0, bl = blocked.get(name) || 0;
        return {
          name,
          journal_calls: jc,
          brain_calls: bc,
          // ★ 被拦过 = 内脑试过但没取到定义。解析自拦截消息的固定句式。
          blocked_attempts: bl,
          mentioned_in_result: inResult.get(name) || 0,
          mentioned_in_prose: inProse.get(name) || 0,
          brain_companions: brainByCompanion.get(name) || [],
          verdict: bc > 0 && jc === 0 ? "blind_to_journal"
            : (jc > 0 && bc === 0 ? "journal_only"
              : (jc > 0 && bc > 0 ? "both" : "mention_only")),
        };
      });

    const anyChatTruncated = perCompanion.some(c => c.window && c.window.truncated);

    return {
      ok: true,
      monitorDir, configDir,
      sources: {
        journal: {
          id: "monitor/tools.jsonl#type=tool_call",
          label: "A · 引擎调用日志",
          what: "引擎写的。覆盖 MCP 调用 + 内脑调用。",
          missing: "★ 它完全不记 find_tools / load_skill / todowrite / selfcompile_* 等（实测 11 个）",
          window: {
            truncated: jr.truncated, size_bytes: jr.sizeBytes, window_bytes: jr.windowBytes,
            bytes_dropped: jr.bytesDropped, lines_dropped: jr.linesDropped, events_read: jr.events.length,
            note_zh: jr.truncated
              ? ("共 " + jr.sizeBytes + " 字节，本次只读尾部 " + jr.windowBytes + " 字节 ⇒ journal_calls 是**下界**")
              : "整个文件都在读取窗口里",
          },
        },
        brain: {
          id: "companions/<uuid>/chat.jsonl#role=assistant.tool_calls[].name",
          label: "B · 内脑自己的对话（真判据）",
          what: "★ 回答「内脑调了什么」的真判据，每条带 id / name / arguments",
          missing: "★ 它只覆盖内脑：MCP 调用不进内脑对话，所以在那批工具上它必然是 0",
          window: {
            companions: perCompanion.length, lines_read: chatLines, total_bytes: chatBytes,
            per_cap_bytes: CHAT_CAP, any_truncated: anyChatTruncated,
            bad_json_lines: chatBad,
            note_zh: anyChatTruncated
              ? ("有同伴的 chat.jsonl 超过 " + CHAT_CAP + " 字节上限 ⇒ brain_calls 也是**下界**")
              : "所有同伴的 chat.jsonl 都在读取范围里",
          },
        },
        mentions: {
          id: "chat.jsonl 里 role=tool 的 content 与 assistant 散文",
          label: "C · 只是「文本里出现过」",
          what: "★ 字符串匹配，**不是调用**",
          missing: "★ 拦截消息每拦一次就贡献一次该工具名，而它一次都没被调 —— 我第一次就栽在这里",
        },
      },
      totals: {
        distinct_tools: allNames.length,
        journal_calls: [...journal.values()].reduce((a, b) => a + b, 0),
        brain_calls: [...brain.values()].reduce((a, b) => a + b, 0),
        blind_to_journal: blindToJournal.length,
        journal_only: journalOnly.length,
        both: both.length,
        mention_only: mentionOnly.length,
        blocked_attempts: [...blocked.values()].reduce((a, b) => a + b, 0),
        chat_bad_json_lines: chatBad,
        // ★ 两个分类是否受窗口影响 —— 页面据此决定要不要在这两个数字上打问号
        window_sensitive: {
          blind_to_journal: jr.truncated,
          journal_only: anyChatTruncated,
        },
      },
      blind_to_journal: blindToJournal,
      journal_only: journalOnly,
      mention_only: mentionOnly,
      // ★★ 这两个分类是**窗口相关**的，必须自带「可能是窗口造成的」说明。
      //   实测证据：同一个 `eat`，用 12MB/无限行的窗口算是「两边都有」，
      //   换成 tools.jsonl 只读尾部 5000 条就变成「监测台看不见」；
      //   `learner_intake` 反过来 —— chat 窗口切掉了那两次，就变成「只有引擎日志有」。
      //   ⇒ 不带这句话，这两个数组会被读成「引擎的结构性缺陷」，
      //     而它可能只是「这一次的窗口没覆盖到」。
      blind_to_journal_note:
        "「内脑调过而这次窗口里 tools.jsonl 没有」。★ 可能是真盲（引擎结构上不记），"
        + "也可能只是那次调用落在本次读取窗口之外 —— journal_calls 被截断时是下界。",
      journal_only_note:
        "「这次窗口里 tools.jsonl 有而 chat.jsonl 没有」。★ 「内脑从没调过它」由 B 证实；"
        + "但「一定是 MCP 调的」不可证 —— 也可能 B 的窗口切掉了那段。",
      tools,
      per_companion: perCompanion.sort((a, b) => b.brain_calls - a.brain_calls),
      caveats: [
        "「A 有 B 没有」⇒ **内脑从没调过它**（由 B 证实）；但「那一定是 MCP 调的」**不可证** —— 也可能是 B 的窗口切掉了那段。两句都要说。",
        "「A 没有 B 有」⇒ **这次窗口里监测台看不见这个工具的调用**。这批里有 todowrite（常驻工具）与 selfcompile_request。注意**窗口也会造成假的看不见**，见 blind_to_journal_note。",
        "两个来源被截断时，所有次数都是**下界**，不是全量。窗口读数在 sources.*.window 里。",
        "mentioned_in_* 是字符串出现次数，**不是调用次数**。",
      ],
    };
  }

  return {
    async all() {
      const now = Date.now();
      if (cache && (now - cache.at) < TTL_MS) {
        return Object.assign({}, cache.payload, { cached: true, cache_age_ms: now - cache.at });
      }
      const payload = await scan();
      cache = { at: now, payload };
      return Object.assign({}, payload, { cached: false, cache_age_ms: 0 });
    },
  };
}
