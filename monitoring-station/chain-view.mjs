import { readdir, readFile, stat } from "node:fs/promises";
import { basename, join, resolve } from "node:path";
import { readJsonlTail } from "./tail-jsonl.mjs";

/**
 * chain-view.mjs · 经验链可见面（2026-10-02 新增）
 *
 * 目的：**让经验链的每一段都肉眼可见，包括断掉的那几段。**
 * 起因：用户要「我怎么验证它确实能够输入输出」+「先接到监测台，让我能看到这些东西」。
 *
 * ★ 设计原则（与普通监控页不同）：**这是体检报告，不是仪表盘。**
 *   能算出来的断点一律用真实数据算；算不出来的（属于游戏侧代码的静态事实）
 *   标 `static:true` 并给 `source`（file:line），**不假装是实测**。
 *   → 「看起来成立」的假象比没有页面更坏。
 *
 * 数据源（★ 全部经 2026-10-01 探针实测确认，不靠推测）：
 *   monitor/instrumentation.jsonl  → 死因 / 监督埋点（type=death / starvation_death）
 *   monitor/expmem.jsonl            → 规划知识（planning_knowledge / planning_policy）
 *                                    ★ 不是 rdd.jsonl —— 实测 rdd.jsonl 里 planning_* 事件 = 0 条
 *   monitor/context.jsonl           → 注入证据（llm_request 行里含 <experience>/<carry>/<learner>）
 *   monitor/learner.jsonl           → 待办 / 携带器 / 判定（noted / carrier_assessment / reviewed）
 *   <config>/experience-*.jsonl     → 正式经验落盘
 *   <config>/knowledge/builtin-experience.jsonl → 内置经验（真 JSONL）
 */

const MAX_BYTES = 4 * 1024 * 1024;
const MAX_EVENTS = 500;

/** 断点卡片：算得出来的用数字，算不出来的标 static 并给 file:line。 */
function gap(id, title, verdict, detail, evidence, extra = {}) {
  return { id, title, verdict, detail, evidence, static: false, ...extra };
}

async function readJsonl(file) {
  try { return await readJsonlTail(file, MAX_BYTES, MAX_EVENTS); }
  catch { return []; }
}

function byType(rows) {
  const m = new Map();
  for (const r of rows) {
    const t = (r && r.type) || "(none)";
    m.set(t, (m.get(t) || 0) + 1);
  }
  return [...m.entries()].sort((a, b) => b[1] - a[1]).map(([type, n]) => ({ type, n }));
}

function quantile(sorted, q) {
  if (!sorted.length) return 0;
  const i = Math.min(sorted.length - 1, Math.max(0, Math.round((sorted.length - 1) * q)));
  return sorted[i];
}

// ─────────────────────────────────────────────────────────────
// ① 死因：★ 关键是把「带结构」与「旧形状」分开，否则 45 条看起来全都有结构
// ─────────────────────────────────────────────────────────────
export async function readDeathView(monitorDir) {
  const rows = await readJsonl(join(monitorDir, "instrumentation.jsonl"));
  // ★ starving 时 type 是 "starvation_death"（RddPlugin.java:712 / InstrumentationEvents.java:30-31）
  //   → 只按 type === "death" 过滤会系统性漏掉饿死那批
  const deaths = rows.filter((r) => /death/i.test(String(r.type || "")));
  const withStruct = deaths.filter((r) => r.data && r.data.deathCauseId !== undefined);
  const legacy = deaths.filter((r) => !(r.data && r.data.deathCauseId !== undefined));
  const kindDist = new Map();
  for (const r of withStruct) {
    const k = (r.data && r.data.deathKind) || "(缺)";
    kindDist.set(k, (kindDist.get(k) || 0) + 1);
  }
  return {
    total: deaths.length,
    byType: byType(deaths),
    withStructured: withStruct.length,
    legacyShape: legacy.length,
    starvationDeath: deaths.filter((r) => r.type === "starvation_death").length,
    deathKindDist: [...kindDist.entries()].sort((a, b) => b[1] - a[1]).map(([kind, n]) => ({ kind, n })),
    structured: withStruct.slice(-60).reverse().map((r) => ({
      timestamp: r.timestamp, type: r.type, task: r.data.task,
      deathCauseId: r.data.deathCauseId, deathCauseMsg: r.data.deathCauseMsg,
      deathKind: r.data.deathKind, deathAttacker: r.data.deathAttacker, reason: r.data.reason,
    })),
    legacySample: legacy.slice(-10).reverse().map((r) => ({
      timestamp: r.timestamp, type: r.type, task: r.data && r.data.task,
      reason: r.data && r.data.reason,
      dataKeys: r.data ? Object.keys(r.data) : [],
    })),
  };
}

// ─────────────────────────────────────────────────────────────
// ② 规划知识（数据源 = expmem.jsonl，不是 rdd.jsonl）
// ─────────────────────────────────────────────────────────────
export async function readPlanningKnowledgeView(monitorDir) {
  const rows = await readJsonl(join(monitorDir, "expmem.jsonl"));
  const pk = rows.filter((r) => r.type === "planning_knowledge");
  const pp = rows.filter((r) => r.type === "planning_policy");
  // ★ 正确判据是 text_chars > 0 —— 不是 injected（两套 schema 混用，见 gap 6）
  const withContent = pk.filter((r) => r.data && Number(r.data.text_chars) > 0);
  const empty = pk.filter((r) => r.data && Number(r.data.text_chars) === 0);
  const noSchema = pk.filter((r) => !(r.data && r.data.text_chars !== undefined));
  const chars = withContent.map((r) => Number(r.data.text_chars) || 0).sort((a, b) => a - b);
  const stageDist = new Map();
  for (const r of pk) {
    const s = (r.data && r.data.stage) || "(none)";
    const hit = r.data && Number(r.data.text_chars) > 0;
    const cur = stageDist.get(s) || { stage: s, total: 0, withContent: 0 };
    cur.total += 1; if (hit) cur.withContent += 1;
    stageDist.set(s, cur);
  }
  return {
    source: "expmem.jsonl",
    note: "规划知识事件写在 expmem.jsonl；实测 rdd.jsonl 里 planning_* 事件 = 0 条。",
    planningKnowledge: pk.length,
    planningPolicy: pp.length,
    withContent: withContent.length,
    emptyByGaps: empty.length,
    noSchema,
    emptyGaps: empty.map((r) => (r.data && r.data.gaps) || []).flat(),
    textChars: { min: quantile(chars, 0), p25: quantile(chars, 0.25), p50: quantile(chars, 0.5), p75: quantile(chars, 0.75), max: quantile(chars, 1) },
    byStage: [...stageDist.values()].sort((a, b) => b.total - a.total),
    policy: pp.slice(-20).reverse().map((r) => r.data || {}),
    sample: withContent.slice(-8).reverse().map((r) => ({
      timestamp: r.timestamp, stage: r.data.stage, text_chars: r.data.text_chars,
      chosen: r.data.chosen, gaps: r.data.gaps, objective_chars: r.data.objective_chars,
      known_failures: r.data.known_failures,
      sources: (r.data.sources || []).map((s) => ({ id: s.id, kind: s.kind, maturity: s.maturity, origin: s.origin })),
    })),
  };
}

// ─────────────────────────────────────────────────────────────
// ③ 注入证据：★ 这就是用户要的「验证它确实能够输入输出」的输入侧
//    数据源 = context.jsonl 的 llm_request 行（实测 <experience> 出现行数 = llm_request 行数）
// ─────────────────────────────────────────────────────────────
function tagSlice(text, tag) {
  const open = "<" + tag;
  const close = "</" + tag + ">";
  const i = text.indexOf(open);
  if (i < 0) return null;
  const j = text.indexOf(close, i);
  if (j < 0) return null;
  return text.slice(i, Math.min(text.length, j + close.length));
}
const clip = (s, n) => (s == null ? null : s.length > n ? s.slice(0, n) + `…[共${s.length}字符]` : s);

export async function readInjectionView(monitorDir) {
  const rows = await readJsonl(join(monitorDir, "context.jsonl"));
  const requests = rows.filter((r) => r.type === "llm_request");
  const states = rows.filter((r) => r.type === "runtime_state");
  // ★ 2026-10-01 实测（别凭猜，两个来源各有不同形态）：
  //  · runtime_state.data.context 是**真字符串**（~4KB），里面是**真字面量** <runtime_state>/<rdd>/<carry>/<current_task>
  //    ——这层不走 Gson 的 HTML 转义，所以能直接字符串匹配。
  //  · <experience> / <learner> 这两个标签在本次运行里**0 命中**：
  //    ExperiencePlugin.contributeState 在 total()==0 时返回空串、整块不出现
  //    （ExperiencePlugin.java:55-57）；LearnerPlugin 同理。
  //    → 「0 命中」本身就是信息：说明经验库/待办统计为 0，标签就没注入。
  //    ——但 llm_request 里 131/131 都含 "experience" 字符串，那是**工具 schema 名字**
  //      （experience_recall / experience_learn），**不是标签**。别把它当成标签命中。
  const tagHit = { runtime_state: 0, rdd: 0, carry: 0, current_task: 0, experience: 0, learner: 0 };
  let latestState = null;
  for (const r of states) {
    const ctx = r.data && typeof r.data.context === "string" ? r.data.context : "";
    if (!ctx) continue;
    for (const tag of Object.keys(tagHit)) if (ctx.includes("<" + tag)) tagHit[tag] += 1;
    if (!latestState || (r.timestamp || "") > (latestState.timestamp || "")) {
      latestState = { timestamp: r.timestamp, ctx, turn: r.data.turn, companion_id: r.data.companion_id };
    }
  }
  // 工具名在请求体里出现的次数（证明工具有挂载，但≠注入正文）
  const toolNameHits = { experience_recall: 0, experience_learn: 0, learner_note: 0, learner_review: 0 };
  for (const r of requests) {
    const s = JSON.stringify(r);
    for (const name of Object.keys(toolNameHits)) if (s.includes(name)) toolNameHits[name] += 1;
  }
  const ctx = latestState ? latestState.ctx : "";
  return {
    source: "context.jsonl",
    runtimeStates: states.length,
    llmRequests: requests.length,
    tagHitCounts: tagHit,
    toolNameHits,
    note: "★ 口径：标签在 runtime_state.data.context（真字符串、真字面量）里统计；工具名在 llm_request 里统计。两者别混 —— llm_request 里的 'experience' 是工具 schema 名，不是注入的标签。",
    experienceTagAbsent: tagHit.experience === 0,
    experienceTagAbsentWhy: "ExperiencePlugin.contributeState 在 total()==0 时返回空串、整块不出现（ExperiencePlugin.java:55-57）。结合经验库面板的 readableByStore=0（磁盘 59 条 / 代码可读 0 条），这正是「经验正文一条都没进上下文」的证据。",
    toolSchemaOnly: "131/131 个 llm_request 都含 experience 字符串，但那是 experience_recall/experience_learn 的工具定义，不是注入的经验正文。",
    latest: latestState ? {
      timestamp: latestState.timestamp, turn: latestState.turn, companion_id: latestState.companion_id,
      ctxChars: ctx.length,
      currentTaskTag: clip(tagSlice(ctx, "current_task"), 800),
      carryTag: clip(tagSlice(ctx, "carry"), 1200),
      rddTagHead: clip((ctx.match(/<rdd>[\s\S]{0,400}/) || [null])[0], 400),
      runtimeStateTag: clip(tagSlice(ctx, "runtime_state"), 600),
    } : null,
  };
}

// ─────────────────────────────────────────────────────────────
// ④ 经验库：★ 核心是**同时认两种形状**，并把「磁盘 N 条 vs 代码可读 0 条」的矛盾报出来
//    起因：experience-*.jsonl 后缀是 jsonl，内容却是**单个 JSON 数组**
//    → ExperienceStore.ensureLoaded() 逐行 getAsJsonObject() 抛 IllegalStateException
//      被 :229 catch(RuntimeException) 吞掉 → 整个文件被跳过
// ─────────────────────────────────────────────────────────────
async function classifyExperienceFile(path) {
  const info = { file: basename(path), bytes: 0, lines: 0, shape: "unknown", entries: 0, readableAsJsonl: false, note: "" };
  let text;
  try {
    const s = await stat(path);
    info.bytes = s.size;
    text = await readFile(path, "utf8");
  } catch (e) { info.note = "读取失败: " + String(e.message); return info; }
  const lines = text.split(/\r?\n/).filter((l) => l.trim());
  info.lines = lines.length;
  if (!lines.length) { info.shape = "empty"; info.note = "空文件"; return info; }
  // 逐行都能 parse 成对象 → 真 JSONL
  let allObjects = true;
  for (const l of lines) {
    try { const o = JSON.parse(l); if (!o || typeof o !== "object" || Array.isArray(o)) { allObjects = false; break; } }
    catch { allObjects = false; break; }
  }
  if (allObjects) {
    info.shape = "jsonl"; info.entries = lines.length; info.readableAsJsonl = true;
    info.note = "真 JSONL —— 逐行 getAsJsonObject() 可读";
    return info;
  }
  // 整文件当一个 JSON 解析 → 数组？
  try {
    const whole = JSON.parse(text);
    if (Array.isArray(whole)) {
      info.shape = "json-array"; info.entries = whole.length; info.readableAsJsonl = false;
      info.note = "★ 后缀是 .jsonl 但内容是**单个 JSON 数组** —— ExperienceStore.ensureLoaded() 逐行 getAsJsonObject() 会抛 IllegalStateException，被 :229 catch(RuntimeException) 吞掉 → 整个文件被跳过，mirror 为空。磁盘有 " + whole.length + " 条，代码读到 0 条。";
      info.entriesSample = whole.slice(0, 3);
      return info;
    }
    if (whole && typeof whole === "object") {
      info.shape = "single-object"; info.entries = 1; info.readableAsJsonl = false;
      info.note = "整文件是单个 JSON 对象";
      return info;
    }
  } catch { /* 落到 unknown */ }
  info.shape = "unknown";
  info.note = "既不是 JSONL 也不是单个 JSON —— 无法解析";
  return info;
}

export async function readExperienceStoreView(configDir) {
  let names = [];
  try { names = await readdir(configDir); } catch (e) {
    return { ok: false, error: "读不了配置目录: " + String(e.message), files: [], conflicts: [] };
  }
  const targets = names.filter((n) => n.startsWith("experience-")).sort();
  const files = [];
  for (const n of targets) files.push(await classifyExperienceFile(join(configDir, n)));
  const builtin = join(configDir, "knowledge", "builtin-experience.jsonl");
  let builtinInfo = null;
  try { builtinInfo = await classifyExperienceFile(builtin); } catch { builtinInfo = null; }

  const arrayShaped = files.filter((f) => f.shape === "json-array" && f.entries > 0);
  const conflicts = arrayShaped.map((f) => ({
    file: f.file,
    onDisk: f.entries,
    readableByStore: 0,
    why: "文件名后缀是 .jsonl，但整份内容是一个 JSON 数组。ExperienceStore.persist() 写的是「每条一行」，ensureLoaded() 读的是「逐行 getAsJsonObject()」—— 形状对不上。",
    consequence: "这些经验在游戏里表现为「0 条经验」：mirror 为空，experience_recall 检索不到，<experience> 标签的 total 统计也是 0。磁盘上明明有 " + f.entries + " 条。",
    fix: "要么把这些文件重写成真 JSONL（一行一条），要么让 ExperienceStore.ensureLoaded() 先尝试把整份当数组读。",
  }));

  return {
    ok: true,
    configDir,
    files,
    builtin: builtinInfo,
    totalOnDisk: files.reduce((a, f) => a + (f.shape === "jsonl" || f.shape === "json-array" ? f.entries : 0), 0),
    readableByStore: files.filter((f) => f.readableAsJsonl).reduce((a, f) => a + f.entries, 0),
    conflicts,
    learnerMemos: await readMemos(configDir),
  };
}

/** 待办（memo）文件：JSON 数组全量重写的那种形状。 */
async function readMemos(configDir) {
  let names = [];
  try { names = await readdir(configDir); } catch { return []; }
  const out = [];
  for (const n of names.filter((x) => x.startsWith("learner-memos-")).sort()) {
    const path = join(configDir, n);
    try {
      const text = await readFile(path, "utf8");
      const arr = JSON.parse(text);
      out.push({ file: n, bytes: text.length, count: Array.isArray(arr) ? arr.length : null, memos: Array.isArray(arr) ? arr.slice(-10) : [] });
    } catch (e) { out.push({ file: n, error: String(e.message) }); }
  }
  return out;
}

// ─────────────────────────────────────────────────────────────
// ⑤ 学习者（待办 / 携带器 / 判定）
// ─────────────────────────────────────────────────────────────
export async function readLearnerView(monitorDir) {
  const rows = await readJsonl(join(monitorDir, "learner.jsonl"));
  const pick = (t) => rows.filter((r) => r.type === t);
  const noted = pick("noted"), carrier = pick("carrier_assessment"), reviewed = pick("reviewed");
  return {
    source: "learner.jsonl",
    total: rows.length,
    byType: byType(rows),
    noted: noted.slice(-20).reverse().map((r) => r.data || {}),
    carrier: carrier.slice(-20).reverse().map((r) => r.data || {}),
    reviewed: reviewed.slice(-20).reverse().map((r) => r.data || {}),
    note: "reviewed 的条数远小于 noted，说明「待办 → 正式经验」这一步没有自动发生（见 gaps 里的 gap-chain-to-experience）。",
  };
}

// ─────────────────────────────────────────────────────────────
// ⑥ 断点清单：★ 能算的用真实数据算，算不了的标 static 并给 file:line
// ─────────────────────────────────────────────────────────────
export async function readGaps(monitorDir, configDir) {
  const [death, planning, injection, store, learner] = await Promise.all([
    readDeathView(monitorDir), readPlanningKnowledgeView(monitorDir),
    readInjectionView(monitorDir), readExperienceStoreView(configDir),
    readLearnerView(monitorDir),
  ]);
  const gaps = [];

  gaps.push({
    id: "gap-chain-to-experience",
    title: "待办 → 正式经验：已接上（0763766c + 3f291ccb）",
    severity: "fixed",
    measured: { noted: learner.byType.find((t) => t.type === "noted")?.n ?? 0, reviewed: learner.byType.find((t) => t.type === "reviewed")?.n ?? 0 },
    detail: "2026-10-02/03 已闭环。B2（0763766c）写了七字段 → ExperienceEntry 形状的映射与硬质量门；E4（3f291ccb）补上了最后一段：学习者自己交 experienceType ⇒ 草稿带 type ⇒ entry_ready_for_store=true。此前草稿的 entry 里没有 type，而 ExperienceStore.learn() 对 type==null 直接抛 ⇒ 草稿一条都落不了库。⚠️ 诚实边界：草稿 → experience_learn → store 那一跳仍要 AI 自己走（RL-20：学习者不自己执行），没有代码替它走。实机 2026-10-02 21:55：7 条真实 memo 跑出 5 条 WRITE_EXPERIENCE，全部交了合法分类。",
    mismatch: {
      learnerSeven: ["mechanism", "preconditions", "failureConditions", "observableSignal", "derivation", "efficiency", "evidence"],
      experienceLearnTen: ["type", "title", "description", "rationale", "root_cause", "recommended_response", "trigger_strings", "tool_names", "tags", "priority"],
      mappingExists: false,
    },
    evidence: ["LearnerReviewTool.java:149-256（零处写经验库）", "LearnerPlugin.java:26-28（设计意图：不写库，交 AI 自己调）", "Experience.java:32-47（七字段）", "ExperienceLearnTool.java:57-98（十字段）", "buildSrc/.../numen-plugin.gradle:37-45（跨插件 import 被封死）"],
    static: true,
  });

  gaps.push({
    id: "gap-death-legacy-shape",
    title: "死因旧形状：不可修复（旧事件不会补字段）",
    severity: "info",
    measured: { total: death.total, withStructured: death.withStructured, legacyShape: death.legacyShape, starvationDeath: death.starvationDeath },
    detail: "⚠️ 本条**不可修复**，别当待办：旧事件不会补字段。45 条死亡里 44 条是旧形状，只会随新事件增加而比例变化。要看比例变化就别指望这一条自己变绿。"
      + (death.starvationDeath ? `另有 ${death.starvationDeath} 条 type=starvation_death（饿死），按 type==="death" 过滤会漏掉。` : ""),
    evidence: ["RddPlugin.java:697-713（四个新字段在这里写出）", "RddPlugin.java:712（starving 时 type 是 starvation_death）", "InstrumentationEvents.java:30-31（两个常量值）"],
    static: false,
  });

  gaps.push({
    id: "gap-unknown-kind",
    title: "死因归因：结构已通，等第二类真实样本",
    severity: "info",
    measured: { deathKindDist: death.deathKindDist },
    detail: "结构已通（deathCauseId 有字段、有分类位），只是**还没有第二类真实样本** —— 目前只有 unknown×1（genericKill）。⚠️ 需要更多真实死亡事件才能验证分类器。**不为此投活、不伪造事件** —— 那会让这条断点变成假事实。",
    evidence: ["DeathCauseClassifier.java:116（generic → UNKNOWN）", "DeathCauseClassifierTest.java（12 项单测，钉的是真实日志里的 5 种句子形态）"],
    static: false,
  });

  gaps.push({
    id: "gap-experience-file-shape",
    title: "经验库：磁盘上有条目、游戏里读成 0 条 —— 已修（f98f90cf）",
    severity: "fixed",
    measured: { totalOnDisk: store.totalOnDisk, readableByStore: store.readableByStore, conflicts: store.conflicts.length },
    detail: "2026-10-02 已修并进游戏验证。ExperienceStore 兼容三种文件形状（逐行 JSONL / 整文件数组 / 单对象），坏行只进 loadedFailed 不混进 loadedLine，加载情况做成公开读数 LoadStats。实测：修前 experience_recall(\"mine diamond ore\") 返 0 条，修后返 5 条真实命中；首次写入即自愈（21+1 条守恒）。⚠️ B1b（experience-reload-double-count）同批修掉：读盘前 mirror.clear()，此前每条都撞「自己那条」被当重复。"
      + (store.conflicts.length
        ? `有 ${store.conflicts.length} 个 experience-*.jsonl 文件：文件名后缀是 .jsonl，但整份内容是**一个 JSON 数组**（共 ${store.totalOnDisk} 条）。ExperienceStore.persist() 写的是「每条一行」，ensureLoaded() 读的是「逐行 getAsJsonObject()」—— 形状对不上，抛 IllegalStateException 后被 catch(RuntimeException) 吞掉，整个文件被跳过。`
        : "未发现形状冲突（experience-*.jsonl 都是真 JSONL）。"),
    conflicts: store.conflicts,
    evidence: ["ExperienceStore.java:241-261（persist 每条一行）", "ExperienceStore.java:210-238（ensureLoaded 逐行解析，:229 吞异常）", "对照：knowledge/builtin-experience.jsonl 是真 JSONL，46 行，BuiltinExperience.parse 逐行读，没这个问题"],
    static: false,
  });

  gaps.push({
    id: "gap-experience-tag-count-only",
    title: "经验正文已进上下文：<experience> 实机 20:34 命中",
    severity: "fixed",
    measured: { tagHitCounts: injection.tagHitCounts, toolNameHits: injection.toolNameHits, readableByStore: store.readableByStore, totalOnDisk: store.totalOnDisk },
    detail: "2026-10-02 已修（E6b d538f55f + bb3247e3）。contributeState 过去只注入 total/verified/generalized 三个数，经验正文一条都没有。现在 ExperienceDirectory 注入 L0 目录层：每行 <n m=成熟度 p=优先级 id=…>标题 ｜ 适用线索</n>。实机（context.jsonl 的 llm_request.messages）：total=22 readable=22 rekeyed=7 listed=6 truncated=true + 6 行目录。bb3247e3 修掉实机抓到的诚实性 bug：22 条只列 6 条却报 truncated=false（调用方给的 hits 已被 recall(maxRows) 砍过一轮，循环里看不到「还有更多」）⇒ 判据改成 listed < total。⚠️ 口径：注入的目录是「可信度兜底序」（成熟度×优先级×近期性），**不是按当前任务筛选的**，<note> 里明说了这一点 —— 它拿得到同伴 UUID 但拿不到任务上下文。"
      + `原因是 ExperiencePlugin.contributeState 在 total()==0 时返回空串、整块不出现（ExperiencePlugin.java:55-57），而 total 来自 mirror —— mirror 为空是因为经验库文件形状对不上（见上一条）。`
      + ` 另注：${injection.llmRequests}/${injection.llmRequests} 个 llm_request 里都有 "experience" 字样，但那是 experience_recall / experience_learn 的**工具 schema 名**，不是注入的经验正文 —— 两者不能混。`,
    consequence: "就算 ExperiencePlugin 改成无条件输出，它也只有 total/verified/generalized 三个计数。经验正文唯一入口是拉取式工具 experience_recall，得干活的 AI 自己想起来调。",
    evidence: ["ExperiencePlugin.java:49-61（只输出三个计数；total()==0 时返回空串）", "ExperienceRecallTool.java:27,61-62（正文唯一入口）", "EntityAgentLoop.java:1647-1652（runtimeStateXml 拼装）", "NumenPlugins.java:86-98（stateFragments 聚合）", "实测：context.jsonl 的 runtime_state.data.context 是真字符串、真字面量，可直接匹配标签"],
    static: false,
  });

  gaps.push({
    id: "gap-no-fingerprint",
    title: "检索指纹：已做（67c79183 的 2-gram + 6396f05d 的 task_fingerprint）",
    severity: "fixed",
    measured: { planningKnowledge: planning.planningKnowledge, withContent: planning.withContent, emptyByGaps: planning.emptyByGaps, textChars: planning.textChars },
    detail: "本条原来记的是两半，现在都不成立了。① 分词：E6a（67c79183）给检索分词加了中文 2-gram，不再是「中文整段连成一个 token」。② 指纹：B4（6396f05d）加了 TaskFingerprint（中文单字集合 + 拉丁整词 → SHA-256 前 16 位），并把 task_fingerprint / scene_fingerprints 写进 planning_knowledge 观测 ⇒ 60 号 A6 判据（「同类任务再次发生 → 同一条被再次命中」）第一次有可比对的标识。⚠️ 实机证据还欠一条：planning_knowledge 里 task_fingerprint 命中 0 —— 已跑过的那些全是旧 jar 产生的，需同伴进计划阶段才会有新事件。⚠️ 指纹只判「完全同一段话」，措辞变了算「近似」，近似交给检索层字面打分。",
    evidence: ["ExperienceKnowledgeSource.java:138-150（buildQuery 整句拼串）", "LexicalExperienceRetriever.java:156-168（tokens 按 [^\\p{L}\\p{N}]+ 切）", "LexicalExperienceRetriever.java:82-115（逐 token contains 打分）", "对照 PlanningKnowledge.java:269-275（规划侧做了 2 字滑窗）"],
    static: true,
  });

  gaps.push({
    id: "gap-rewritten-query-unused",
    title: "rewritten_query 已接进检索（bde27cfb），实机未验",
    severity: "warn",
    measured: null,
    detail: "⚠️ **本条 2026-10-03 的原标题「架构上无法参与检索（不是忘了接）」是错的，已更正 —— 而且错在推理上，不只是措辞。** 原判据说「plugins/learner 刻意不依赖 experience-core ⇒ 两个 mod 之间没有数据通道」，**这条推理不成立**：两个 mod 之间的通道就是**草稿 JSON 本身**。ExperienceDraft 的类注释白纸黑字写着「plugins/learner 刻意不依赖 experience-core（嵌套会 JPMS ResolutionException）… 所以这里只保证键名与 ExperienceEntry.toJson() 完全一致」—— 也就是说，不依赖恰恰是它**产 JSON 而不是产 ExperienceEntry 对象**的原因，通道一直都在。真因是**映射里少了一行**（和 B2「映射代码从来没写过」同一族洞）：写端本来就通（LearnerReviewer §7 明确要 LLM 给「1 到 3 个改写检索词，替代向量检索的检索键」，Verdict 解析后也渲染进 reviewed 回执），读端本来就通（LexicalExperienceRetriever 读 triggerStrings，+3.0/词 = 全字段最强信号，整串出现在查询里再 +2.0/个），**断的只是中间**。修法 bde27cfb：ExperienceDraft.from() 把 v.rewrittenQuery() 收进 trigger_strings，截断到 40 字，计数按 LinkedHashSet.add() 的真实新增条数报，explain() 暴露两个读数。**原处置「保留字段」这个方向没错但理由是错的**：保留是因为 monitor-app.js:301-302 还在渲染它（删字段会让页面 undefined.join 抛异常），不是因为它接不进去。⚠️ 诚实边界：① jar 未部署、游戏未跑，**没有任何一条真实条目带 rewritten_query 落盘的实机证据**；② 60 号判据 A6「触发得回来」仍欠一条实机证据（B4 的 planning_knowledge 实机证据也还欠）；③ **它注定帮不到「触发它的那次检索」** —— rewritten_query 是回顾性的（学完之后才产出），检索是前瞻性的（规划期就要召回），只能让下一次同类场景命中，这是「学习」本身的性质不是缺陷；④ 质量取决于 LLM 是否真给，现在 0 会如实报出。★ 同一批次还修了一个既有红：ENTRY_KEYS 漏了 E8 的 5 个键 ⇒ **:plugins:learner:test 从 E8 批次（aba150dc）起就没绿过**，而 E8 验收记录写的是「全绿」（那批只跑了 experience 侧，没跑 learner 侧）。",
    evidence: ["LearnerReviewer.java:58（提示词第 7 条：改写检索词 = 替代向量检索的检索键）", "Verdict.java:122-141（解析，且已滤掉 null/blank）", "LearnerReviewTool.java:265-266（渲染进 reviewed 回执）", "ExperienceDraft.java（★ B5：rewrittenQuery → entry.trigger_strings；类注释写明「产 JSON 而非产对象」正是通道本身）", "LexicalExperienceRetriever.java:87（读 triggerStrings +3.0/词）+ :117-124（整串再 +2.0/个）", "LexicalExperienceRetrieverTest.triggerStringHitsRankAboveTitleHits（读端早就有测试钉住）", "ExperienceDraftGateTest（6 条新测试，钉的是槽位不是「JSON 里出现过这个词」）", ":plugins:learner:test 11 类 157 测试 0 失败（本次真跑）", "monitor-app.js:301-302（保留字段的真实理由：页面在渲染它）"],
    static: true,
  });

  gaps.push({
    id: "gap-seven-fields-not-a-gate",
    title: "七字段门禁已能拦人，但「填了≠分对了」仍未验证",
    severity: "warn",
    measured: { reviewed: learner.byType.find((t) => t.type === "reviewed")?.n ?? 0 },
    detail: "⚠️ 部分销账。B8（0763766c）给了硬质量门 ExperienceQualityGate（Q0–Q6，无条件跑：写法是 WRITE_EXPERIENCE 却没交七字段必被拒）；E4（3f291ccb）让 acceptable() 第 2 批起还要 typeComplete()。实机 2026-10-02 21:55：2 条 NO_ACTION 被 REJECT + publishable=false，5 条 WRITE_EXPERIENCE 是 REVIEW_REQUIRED（undecidable=3：Q2/Q3/Q4 需人看），**不是自动 PASS** ⇒ 门禁真的在拦。但「填了 ≠ 分对了 / 内容对」那一半仍未解决：分类准不准目前只有 1 轮 5 条样本，没独立核对过。",
    evidence: ["Experience.java:117-124（requiredComplete / acceptable 定义）", "LearnerReviewTool.java:204-210（只当报告字段）", "LearnerReviewTool.java:179（仍然 commit 出队）"],
    static: true,
  });

  gaps.push({
    id: "gap-no-death-snapshot",
    title: "死亡那一刻的现场快照：代码已做（4bb98d5b），实机未验",
    severity: "warn",
    measured: null,
    detail: "⚠️ **部分销账 —— 代码与单测已过，实机那一半还没做**。用户要「整个事情的链路都要冻结」，原先每条 death 只带最后一刻状态，前面「怎么走到死的」零留痕。B6 加了 TraceRing：每同伴一个有界环形窗口（容量 32），死亡类事件（death/starvation_death）把该同伴窗口的快照冻结进**同一行的 data.recent_trace**，快照在推进窗口**之前**取 ⇒ 只含「死之前」的事；写盘成功才推进窗口（写失败也推进会让快照与落盘不一致，比少一条更糟）。① **不重复造轮子**：RddDeathLedger + RddDeathLedgerStore 管的是「多次死亡之间」（第几次死/死在哪/掉了什么/是否已确认回收），**没有任何「死亡之前发生过哪些事件」字段**；本条补的是「一次死亡之内」的前因，两者正交。② 也没另建落盘文件：instrumentation.jsonl 本身就是持久历史，缺的只是「死亡那一刻把它一起带走」；另建文件会引入新的形状/损坏风险（B1 的教训）。③ 诚实口径：窗口挤过会明写 trace_truncated + trace_note，不让人把「窗口里有 32 条」误读成「只发生过 32 条」；快照只带 6 个字段的精简集不是全量 data，要全量查 jsonl。❌ **仍未做**：jar 未部署、游戏未跑，**没有任何一条真实 death 事件带 recent_trace 的实机证据**；容量 32 是权衡值未经实机校准（同 DEMOTE_AFTER_CONSECUTIVE_FAILURES=3 纪律）。⚠️ 与 gap-death-legacy-shape 同理：旧事件不会补字段 —— 已有那 45 条 death 永远没有 recent_trace，本条只能**向前**销账，看历史分布别指望它变。",
    evidence: ["TraceRing.java（纯 JVM 环形窗口：DEFAULT_CAPACITY=32 / TRACE_FIELDS 六字段 / everTruncated）", "RddInstrumentation.java:111-127（publishAt：快照在 push 前冻结进 data，写成功才推进）", "RddInstrumentation.java:73-79（按同伴分组；全局窗口会掺别人的因果）", "TraceRingTest.java（14 项，含「快照不含死亡本身」「写失败不进窗口」「分组不串链」）", ":plugins:rdd:test 全量 195 测试 0 失败", "对照 RddDeathLedger.java:65-68（Death 字段无事件链，正交）", "RddPlugin.java:653（坐标）/ :648 deathInstantCounts / :679 isStarvingDeath", "用户原话：「需要冻结整个前面不只是前面 5 秒钟，更是整个事情的链路都要冻结」"],
    static: true,
  });

  return gaps;
}

// ─────────────────────────────────────────────────────────────
// 聚合入口
// ─────────────────────────────────────────────────────────────
export function createChainView(monitorDir) {
  const configDir = resolve(monitorDir, "..");
  return {
    configDir,
    async death() { return readDeathView(monitorDir); },
    async planning() { return readPlanningKnowledgeView(monitorDir); },
    async injection() { return readInjectionView(monitorDir); },
    async experienceStore() { return readExperienceStoreView(configDir); },
    async learner() { return readLearnerView(monitorDir); },
    async gaps() { return readGaps(monitorDir, configDir); },
    async all() {
      const [death, planning, injection, experienceStore, learner, gaps] = await Promise.all([
        readDeathView(monitorDir), readPlanningKnowledgeView(monitorDir),
        readInjectionView(monitorDir), readExperienceStoreView(configDir),
        readLearnerView(monitorDir), readGaps(monitorDir, configDir),
      ]);
      return { monitorDir, configDir, death, planning, injection, experienceStore, learner, gaps, timestamp: new Date().toISOString() };
    },
  };
}
