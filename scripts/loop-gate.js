#!/usr/bin/env node
/**
 * loop-gate.js — 九步流硬门禁（发目标 → 写计划 → 写代码 → 游戏验证 → 记录 → 轮回）
 *
 * ## 为什么要有这个（2026-09-29 立）
 *
 * `flow-gate.js` 只管「闭环四步 + 记录两件」，结果一批 AI 可以：
 *   - 完全不做计划阶段的外脑商讨（W 层零产出照样 FLOW_COMPLETE）
 *   - 计划时一次不查经验库
 *   - 不跑进游戏巡检（04 §五 四层监察 / 效率监察 / redline-watch）
 *   - 不写 09-更新日志
 *   - 不做契约/压力回归
 *   - 不做数据清理、不对照不回退清单
 * 而 flow-gate 依然给 VERDICT=FLOW_COMPLETE。
 *
 * 实证（2026-09-29 本轮自查）：上面 7 条我一条都没做，flow-gate 照样放行。
 * 根因是它**只检查「今天有某个文件」，不检查「这个文件是本轮为本任务产出的」**。
 *
 * ## 本门禁的设计原则
 *
 * **每项都必须能证明是「本轮产出」**，两条腿走路：
 *   ① 时间：mtime >= --since（默认今天 00:00）
 *   ② 内容签名：文件正文必须含 --keyword（默认取本轮 git HEAD 的 hash 前缀 + 模块名）
 *      —— 只有 mtime 没有内容签名 = 旧文件改个时间就能骗过门禁（这正是 flow-gate 的漏洞）
 *
 * ## 判定级别
 *   block  缺了就不许收工、不许对外说「走完流程了」
 *   warn   缺了要写进测试日志说明为什么可以不补
 *
 * 用法：
 *   node loop-gate.js --since 2026-09-29 [--keyword learner] [--head <sha>] [--json]
 *   node loop-gate.js --self-test
 */

'use strict';
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const SCRIPTS = __dirname;
const REPO = 'D:/rdd/workspace/minecraft-numen';
const ARCH = 'D:/rdd/docs/rdd-docs/rdd架构';
const UNIFIED = path.join(ARCH, '自编译统一版');
const LOGS = path.join(ARCH, '测试日志.md');
const UPDATELOG = path.join(UNIFIED, '09-更新日志.md');
// 巡检文档落点（2026-09-30 修正）。
//   原来指向 ARCH/特殊模式/进游戏巡检规范与异常记录.md —— 该目录当天已整体移到
//   ARCH/_废弃-特殊模式-20260930/（AGENTS §10 记的"已废弃"），所以这一项**永远查不到**，
//   属于门禁自己指向了死路径，不是 AI 没写巡检。
//   现行落点：自编译统一版/_stage/ 下按批次命名的巡检记录。
const INSPECTION_GLOB_DIRS = [
  path.join(ARCH, '_stage'),   // 2026-10-01 整理后 _stage 上移到 rdd架构 这一层（原先挂在 自编译统一版 下面）
  path.join(ARCH, '_废弃-特殊模式-20260930'),
];
// W 层回稿的落点。
// ★ 2026-10-01 修「门禁指错路」：原来指向 rdd架构\brain-replies\，
//   但 2026-09-30 外脑落盘已统一迁到 rdd-selfcompile\report\w-replies\
//   （w-brain-drive.js 的 W_REPLY_DIR 也已改指那里），而 brain-replies\ 下
//   只剩 1 个历史文件 → **②-2 W 层外脑这一项永远判「没落盘」**。
//   现在扫两个地方：新的 report\w-replies\ 为主，历史 brain-replies\ 兜底。
const WREPLY_DIRS = [
  path.join(SCRIPTS, '..', 'report', 'w-replies'),
  path.join(ARCH, 'brain-replies'),
];
const REPORT_DIR = path.join(SCRIPTS, '..', 'report');
const INSTANCE_ROOT = 'D:/rdd/workspace/rdd-selfcompile/instances';

// ---------- 参数 ----------
function arg(name, dflt) {
  const i = process.argv.indexOf('--' + name);
  if (i < 0) return dflt;
  const v = process.argv[i + 1];
  return (v === undefined || v.startsWith('--')) ? true : v;
}
const SELF_TEST = process.argv.includes('--self-test');
const AS_JSON = process.argv.includes('--json');

function todayStr() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
const SINCE_DATE = String(arg('since', todayStr()));
const SINCE = new Date(SINCE_DATE + 'T00:00:00').getTime();
const KEYWORD = String(arg('keyword', ''));
const HEAD = String(arg('head', ''));

// ---------- 工具 ----------
const rows = [];
function chk(sev, name, ok, detail) { rows.push({ sev, name, ok: !!ok, detail: detail || '' }); }
function safe(fn, dflt) { try { return fn(); } catch (e) { return dflt; } }
function exists(p) { try { fs.accessSync(p); return true; } catch (e) { return false; } }

function mtimeOf(p) {
  try { return fs.statSync(p).mtimeMs; } catch (e) { return 0; }
}
function isFresh(p) { return mtimeOf(p) >= SINCE; }

/** 文件 mtime 新鲜 **且** 正文含签名（keyword / head / 模块名）才算「本轮产出」。 */
function hasSignature(text) {
  const t = String(text || '');
  const sigs = [KEYWORD, HEAD].filter(Boolean).map(s => s.slice(0, 12));
  if (sigs.length === 0) return false;   // 没给签名 → 不认，防「只靠 mtime 骗过」
  return sigs.some(s => t.includes(s));
}

/** 本轮产出判定：mtime 新鲜 + 内容带签名。 */
function producedToday(p, extraNeedles) {
  if (!exists(p)) return { ok: false, why: '文件不存在: ' + p };
  if (!isFresh(p)) return { ok: false, why: `mtime ${new Date(mtimeOf(p)).toLocaleString('zh-CN')} 早于 ${SINCE_DATE}（旧文件不算本轮）` };
  const text = safe(() => fs.readFileSync(p, 'utf8'), '');
  const sigs = [KEYWORD, HEAD].filter(Boolean).map(s => s.slice(0, 12));
  const extra = extraNeedles || [];
  if (!sigs.some(s => text.includes(s)) && !extra.some(e => text.includes(e))) {
    return { ok: false, why: `内容不含本轮签名（${sigs.join('/') || '未指定'}）→ 只是 mtime 新，内容不是本轮产物` };
  }
  return { ok: true, why: '' };
}

/** 目录下本轮产出的文件（mtime + 文件名带签名）。 */
function producedInDir(dir, nameRe, contentNeedles) {
  if (!exists(dir)) return { hits: [], note: '目录不存在: ' + dir };
  const hits = [];
  let all = [];
  safe(() => { all = fs.readdirSync(dir); }, []);
  for (const f of all) {
    const p = path.join(dir, f);
    if (!safe(() => fs.statSync(p).isFile(), false)) continue;
    if (!isFresh(p)) continue;
    if (nameRe && !nameRe.test(f)) continue;
    const text = safe(() => fs.readFileSync(p, 'utf8'), '');
    const sigs = [KEYWORD, HEAD].filter(Boolean).map(s => s.slice(0, 12));
    const ok = sigs.length === 0 ? false : sigs.some(s => text.includes(s) || f.includes(s));
    if (ok || (contentNeedles || []).some(n => text.includes(n))) hits.push(f);
  }
  return { hits, note: '' };
}

function gitLines(args) {
  return safe(() => execFileSync('git', args, { cwd: REPO, encoding: 'utf8' }).trim(), '');
}
function gitCountToday() {
  const out = safe(() => execFileSync('git', ['log', '--since=' + SINCE_DATE + 'T00:00:00', '--oneline'], { cwd: REPO, encoding: 'utf8' }), '');
  return out.split('\n').filter(l => l.trim()).length;
}
function listFilesUnder(root, depthLimit) {
  const out = [];
  if (!exists(root)) return out;
  const walk = (d, dpt) => {
    if (dpt > (depthLimit || 2)) return;
    let ents = [];
    safe(() => { ents = fs.readdirSync(d, { withFileTypes: true }); }, []);
    for (const e of ents) {
      const f = path.join(d, e.name);
      if (e.isDirectory()) walk(f, dpt + 1);
      else out.push(f);
    }
  };
  walk(root, 0);
  return out;
}

// ---------- ① 发目标 ----------
(function goal() {
  const commits = gitCountToday();
  const hasSelfReport = /档位|工具级|模块级|架构级/.test(safe(() => gitLines(['log', '--since=' + SINCE_DATE + 'T00:00:00', '-p']), ''))
    || (exists(LOGS) && isFresh(LOGS));
  chk(hasSelfReport ? 'ok' : 'block',
    '① 发目标（档位自报落档，不只在对话里说）',
    hasSelfReport,
    hasSelfReport ? `测试日志本轮有档位记录；本轮 commit=${commits}` : '测试日志无本轮档位记录（只在对话里自报不算数）');
})();

// ---------- ② 写计划（用户重点：记忆 + W层 + C层 + 桥）----------
(function plan() {
  // 2.1 计划文档本轮产出
  const planFiles = listFilesUnder(UNIFIED, 1).filter(f => /\d{2}-.*\.md$/.test(f));
  let planHit = null, planWhy = '本轮无带签名的计划文档';
  for (const f of planFiles) {
    const r = producedToday(f, ['第二批', '学习者', '计划']);
    if (r.ok) { planHit = f; planWhy = path.basename(f); break; }
  }
  chk(planHit ? 'ok' : 'block', '②-1 计划文档是本轮产出（非旧文件）', planHit, planWhy);

  // 2.2 W 层外脑（网页端 AI）产物落盘 —— 零成本，必须做
  const w = producedInDir(WREPLY_DIRS[0], /\.(md|txt|json)$/i, ['W 层', 'GLM', 'Kimi', '网页外脑', 'askall', 'synth']);
  const wFresh = WREPLY_DIRS.flatMap((d) => listFilesUnder(d, 0)).filter(f => isFresh(f));
  chk(w.hits.length > 0 ? 'ok' : 'block',
    '②-2 W 层外脑（网页 GLM/Kimi）本轮商讨已落盘',
    w.hits.length > 0,
    w.hits.length > 0 ? `brain-replies: ${w.hits.join(', ')}`
      : `brain-replies/ 今日新文件 ${wFresh.length} 个但无带签名产物（今日最新 = ${wFresh.length ? path.basename(wFresh[wFresh.length - 1]) : '无'}）。` +
        `规范：自动模式铁律「W 自主轮回：GLM/Kimi 可用」，省额度不是跳过理由，网页外脑零成本`);

  // 2.3 C 层外脑（codex）产物落盘
  const c = producedInDir(REPORT_DIR, /\.(md|txt)$/i, ['Codex', 'codex', '外脑审']);
  chk(c.hits.length > 0 ? 'ok' : 'block',
    '②-3 C 层外脑（codex）本轮商讨已落盘',
    c.hits.length > 0,
    c.hits.length > 0 ? `report/: ${c.hits.join(', ')}` : `report/ 今日无带签名产物（查过 ${path.basename(REPORT_DIR)}）`);

  // 2.4 经验库检索（计划时就要查，不是出事了才查）—— 06-检查清单「过程中」明文要求
  //     留痕可放 scripts/ 或 report/（经验库检索留痕归 report 更合理）
  const memDirs = [SCRIPTS, REPORT_DIR];
  let memHit = [];
  for (const d of memDirs) {
    memHit = memHit.concat(listFilesUnder(d, 1).filter(f =>
      /memory|经验库|memory-recall|memory-search|经验.*留痕|检索留痕/i.test(path.basename(f)) && isFresh(f)
      && hasSignature(safe(() => fs.readFileSync(f, 'utf8'), ''))));
  }
  chk(memHit.length > 0 ? 'ok' : 'block',
    '②-4 计划时查过经验库（search_nodes 留痕）',
    memHit.length > 0,
    memHit.length > 0 ? `留痕: ${memHit.map(f => path.basename(f)).join(', ')}`
      : '未找到本轮经验库检索留痕（查过 scripts/ 与 report/）。06-检查清单「过程中」：' +
        '经验库检索——遇问题先 search_nodes 定向取经验。（只写不查 = 记了但没受益；须把 search 结果落成文件）');

  // 2.5 架构不偏：自问几问
  let selfQ = { ok: false, why: '' };
  for (const f of planFiles) {
    const r = producedToday(f, ['自问', '遗漏', '怎么办', '为何这样设计', '达成目的']);
    if (r.ok) { selfQ = { ok: true, why: path.basename(f) }; break; }
  }
  if (!selfQ.ok) {
    const t = safe(() => fs.readFileSync(LOGS, 'utf8'), '');
    if (/自问|遗漏|怎么办\?/.test(t) && isFresh(LOGS)) selfQ = { ok: true, why: '测试日志本轮含自问几问' };
  }
  chk(selfQ.ok ? 'ok' : 'warn',
    '②-5 架构不偏：补全自问几问（怎么办/为何这样设计/达成目的吗/遗漏吗）',
    selfQ.ok, selfQ.ok ? selfQ.why : '本轮未见自问几问落档（10-总流程 自动模式铁律 3）');
})();

// ---------- ③ 写代码（工程规范）----------
(function code() {
  const commits = gitCountToday();
  chk(commits > 0 ? 'ok' : 'block', '③-1 本轮有按模块 commit', commits > 0, `commits=${commits}`);

  // 3.2 测试结果：0 失败是硬要求
  const tr = listFilesUnder(REPO, 6).filter(f => /TEST-.*\.xml$/.test(path.basename(f)) && isFresh(f));
  let total = 0, fails = 0;
  for (const f of tr) {
    try {
      const x = JSON.parse('{"r":null}');
      void x;
      const xml = fs.readFileSync(f, 'utf8');
      const m = /tests="(\d+)"/.exec(xml) || /<testsuite[^>]*tests="(\d+)"/.exec(xml);
      const fl = /failures="(\d+)"/.exec(xml);
      const er = /errors="(\d+)"/.exec(xml);
      if (m) total += parseInt(m[1], 10);
      if (fl) fails += parseInt(fl[1], 10);
      if (er) fails += parseInt(er[1], 10);
    } catch (e) { /* 单个 xml 坏了不判全局 */ }
  }
  chk(total > 0 && fails === 0 ? 'ok' : 'block',
    '③-2 本轮测试有结果且 0 失败',
    total > 0 && fails === 0, `tests=${total} failures/errors=${fails}（xml 样本 ${tr.length}）`);

  // 3.3 解释性维护说明 —— 必须带本轮签名（旧文件不算，这是 flow-gate 的漏洞）
  const docCandidates = listFilesUnder(UNIFIED, 1).filter(f => /维护说明|运维|\d{2}-.*\.md$/.test(path.basename(f)));
  let docHit = null, freshButUnsigned = [];
  for (const f of docCandidates) {
    if (!isFresh(f)) continue;
    const r = producedToday(f, []);
    if (r.ok) { docHit = f; break; }
    freshButUnsigned.push(path.basename(f));
  }
  chk(docHit ? 'ok' : 'block',
    '③-3 解释性维护说明是「本轮」产出（工程规范第 8 条）',
    docHit,
    docHit ? path.basename(docHit)
      : `今日 mtime 新但内容不含本轮签名的说明：${freshButUnsigned.join(', ') || '无'}。` +
        '注意：flow-gate.js 的同名检查只匹配「今天有某个 .md」，旧文件改名/改时间即可骗过 —— 这就是本项存在的原因');

  // 3.4 契约 + 压力回归（06 收工：三类回归）。测试在仓库 test 目录，不在 scripts/。
  const stressCandidates = listFilesUnder(REPO, 8)
    .filter(f => /(stress|contract|契约|压力)/i.test(path.basename(f)) && isFresh(f));
  const stressRun = stressCandidates.filter(f => {
    // 光有测试文件不算数：得真跑过 —— 看 test-results 目录里有没有对应的 xml
    const base = path.basename(f).replace(/\.java$/, '');
    return listFilesUnder(REPO, 8).some(r =>
      /TEST-.*\.xml$/.test(path.basename(r)) && r.includes(base) && isFresh(r));
  });
  chk(stressRun.length > 0 ? 'ok' : 'block',
    '③-4 契约/压力回归：测试存在 **且跑过**（06 收工「三类回归」）',
    stressRun.length > 0,
    stressRun.length > 0
      ? `已跑: ${stressRun.map(f => path.basename(f)).join(', ')}`
      : `本轮无「跑过」的契约/压力回归。测试文件 ${stressCandidates.length} 个` +
        `（${stressCandidates.map(f => path.basename(f)).slice(0, 3).join(', ') || '无'}），` +
        '但 test-results 里没有对应 xml = 没真跑。光有文件不算证据。');

  // 3.5 不回退清单
  const noRegress = listFilesUnder(ARCH, 2).filter(f => /不回退|RL-1|红线健康/.test(path.basename(f)));
  const noRegressFresh = noRegress.filter(isFresh);
  chk(noRegressFresh.length > 0 ? 'ok' : 'block',
    '③-5 不回退清单存在且本轮实际对照过（大链路 + RL-1..8）',
    noRegressFresh.length > 0,
    noRegress.length > 0
      ? `清单: ${noRegress.map(f => path.basename(f)).join(', ')} —— ${noRegressFresh.length ? '本轮已更新对照' : '⚠️ 本轮未对照（清单在但没人看，等于没有）'}`
      : '搜不到不回退清单文件。工程规范第 9 条要求维护「主要功能不回退清单」（大链路 + RL-1..8），拆解前必须对照');
})();

// ---------- ④ 游戏验证 ----------
(function game() {
  // 4.1 监测台本轮事件（真实世界证据，不是「文件存在」）
  let evHit = [], evTotal = 0;
  for (const inst of ['test', 'gpt']) {
    const md = inst === 'gpt'
      ? 'D:/rdd/mc/The Best of Twilight Forest/config/numen/monitor'
      : path.join(INSTANCE_ROOT, inst, 'config', 'numen', 'monitor');
    if (!exists(md)) continue;
    let files = [];
    safe(() => { files = fs.readdirSync(md).filter(f => f.endsWith('.jsonl')); }, []);
    for (const f of files) {
      const p = path.join(md, f);
      if (!isFresh(p)) continue;
      const txt = safe(() => fs.readFileSync(p, 'utf8'), '');
      const lines = txt.trim().split('\n').filter(Boolean);
      evTotal += lines.length;
      if (lines.length && (KEYWORD ? txt.includes(KEYWORD) : true)) evHit.push(`${inst}/${f}(${lines.length})`);
    }
  }
  chk(evHit.length > 0 ? 'ok' : 'block',
    '④-1 游戏内真实事件证据（monitor jsonl 本轮有内容）',
    evHit.length > 0,
    evHit.length > 0 ? `${evHit.length} 个文件有本轮内容，共 ${evTotal} 行：${evHit.slice(0, 5).join(', ')}`
      : `本轮无任何 monitor jsonl 新内容（不能拿「文件存在」当验证）`);

  // 4.2 verify verdict 必须不是 UNVERIFIED（闭环「验证节」不能空着）
  const muts = path.join(SCRIPTS, '..', 'mutations');
  let verdicts = [];
  safe(() => {
    for (const d of fs.readdirSync(muts)) {
      const vp = path.join(muts, d, 'verdict.json');
      if (exists(vp) && isFresh(vp)) {
        try { verdicts.push({ id: d, v: safe(() => JSON.parse(fs.readFileSync(vp, 'utf8')), {}) }); } catch (e) { }
      }
    }
  }, []);
  const verified = verdicts.filter(x => /^(?!UNVERIFIED)VERIFIED/.test(String(x.v.verdict || '').trim().toUpperCase()));
  chk(verified.length > 0 ? 'ok' : 'block',
    '④-2 verify.ps1 出过 VERIFIED（闭环验证节非空）',
    verified.length > 0,
    verified.length > 0 ? verified.map(x => `${x.id}=${x.v.verdict}`).join(', ')
      : `本轮 ${verdicts.length} 个 mutation，verdict 全是 ${[...new Set(verdicts.map(x => x.v.verdict || '(无)'))].join('/') || '(无 verdict.json)'}。` +
        '「编译成功」与「行为成功」之间必须有证据；手动 MCP 驱动不算闭环 VERIFIED');

  // 4.3 进游戏巡检（04 §五 四层监察 / 06 进游戏清单）
  const eff = listFilesUnder(SCRIPTS, 1).filter(f => /^task-efficiency\..*\.jsonl$/.test(path.basename(f)) && isFresh(f));
  chk(eff.length > 0 ? 'ok' : 'block',
    '④-3 任务效率监察有本轮输出（04 §五 第三层）',
    eff.length > 0,
    eff.length > 0 ? eff.map(f => path.basename(f)).join(', ') : '未跑 task-efficiency-watch.js（subtask_flat/assets_flat/inv_flat 三个独立信号）');

  // 4.4 巡检异常实时写入巡检文档（04 铁律「进游戏不得直接停」）
  //     2026-09-30：在多个候选目录里找「本轮新鲜 + 含签名」的巡检记录
  //     （单一硬编码路径已改成 INSPECTION_GLOB_DIRS，见上面注释）。
  const inspCandidates = INSPECTION_GLOB_DIRS
    .filter(d => exists(d))
    .flatMap(d => listFilesUnder(d, 1))
    .filter(f => /巡检|进游戏巡检|inspect/i.test(path.basename(f)))
    .filter(f => isFresh(f) && hasSignature(safe(() => fs.readFileSync(f, 'utf8'), '')));
  const insp = inspCandidates.length > 0;
  chk(insp ? 'ok' : 'block',
    '④-4 巡检异常实时写入巡检文档（04 铁律「进游戏不得直接停」）',
    insp,
    insp ? inspCandidates.map(f => path.basename(f)).join(', ')
         : '候选目录均无本轮新鲜且带签名的巡检记录: ' + INSPECTION_GLOB_DIRS.join(' | '));

  // 4.5 先确认 LLM 有脑（06 进游戏：HTTP 401/402/5xx）
  //     只认「本轮实测」记法 —— 不能靠正文里引用旧记录里的 "402" 字样蒙混过关。
  //     要求形如： LLM_BRAIN_CHECK ok / LLM_BRAIN_CHECK 402
  const logTail = safe(() => fs.readFileSync(LOGS, 'utf8').slice(-30000), '');
  const brainHit = /LLM_BRAIN_CHECK[^\n]{0,200}/.exec(logTail);
  chk(brainHit ? 'ok' : 'block',
    '④-5 确认 LLM 有脑（实测查过 401/402/5xx，须留痕）',
    !!brainHit,
    brainHit ? `留痕: ${brainHit[0].slice(0, 120)}`
      : '未找到本轮 LLM_BRAIN_CHECK 留痕。06 进游戏清单：先确认 LLM 有脑再判行为。' +
        '2026-09-28 实测：DeepSeek 402 × 49、0 成功 → 看起来像执行层慢，真因是没钱。' +
        '（正文里引用旧记录的 "402" 字样不算数，必须是本轮实测标记）');
})();

// ---------- ⑤ 记录 ----------
(function record() {
  const lg = producedToday(LOGS, ['档位', '目标', '改动', '测试', '证据']);
  chk(lg.ok ? 'ok' : 'block', '⑤-1 测试日志本轮条目带签名', lg.ok, lg.ok ? 'ok' : lg.why);

  const ul = producedToday(UPDATELOG, ['更新', '新增', '变更']);
  chk(ul.ok ? 'ok' : 'block',
    '⑤-2 09-更新日志本轮有登记',
    ul.ok, ul.ok ? path.basename(UPDATELOG)
      : `未更新（mtime ${exists(UPDATELOG) ? new Date(mtimeOf(UPDATELOG)).toLocaleString('zh-CN') : 'N/A'}）。06 收工明文：写 测试日志.md + 09-更新日志.md`);

  // 数据清理（06 收工 + 16）
  const cleanup = exists(path.join(SCRIPTS, 'cleanup-logs.ps1'));
  chk(cleanup ? 'warn' : 'block',
    '⑤-3 数据清理策略有脚本可跑（cleanup-logs.ps1，默认只报告）',
    cleanup, cleanup ? '脚本存在，本轮应至少跑一次 --report' : 'cleanup-logs.ps1 不存在');
})();

// ---------- ⑥ 轮回 ----------
(function loop() {
  let hit = null;
  // 深度 2：next-round plans live in 自编译统一版/xx-下一轮计划-*.md (ARCH/统一版/ = depth 2).
  // Scanning only depth 1 silently missed them, so the gate blocked a round that
  // had actually written its loop-back list.
  const cands = [LOGS]
    .concat(listFilesUnder(ARCH, 1).filter(f => /交接|handoff|未完成|待办/.test(f)))
    .concat(listFilesUnder(UNIFIED, 1).filter(f => /下一轮|未完成|待办|轮回|next/i.test(path.basename(f))));
  for (const f of cands) {
    if (!exists(f) || !isFresh(f)) continue;
    const t = safe(() => fs.readFileSync(f, 'utf8'), '');
    if (hasSignature(t) && /未完成|待办|下一步|下一轮|轮回/.test(t.slice(-30000))) { hit = f; break; }
  }
  chk(hit ? 'ok' : 'block',
    '⑥ 轮回回「计划」：未完成项/下一轮计划已落档',
    hit, hit ? path.basename(hit) : '未找到本轮带签名的「未完成/下一轮」条目 —— 轮回点缺失，下一个 AI 无从接手');
})();

// ---------- 输出 ----------
const passN = rows.filter(r => r.ok).length;
const blockN = rows.filter(r => !r.ok && r.sev === 'block').length;
const warnN = rows.filter(r => !r.ok && r.sev === 'warn').length;
const verdict = blockN === 0 ? 'LOOP_COMPLETE' : 'NOT_DONE';

if (AS_JSON) {
  console.log(JSON.stringify({ since: SINCE_DATE, keyword: KEYWORD, verdict, pass: passN, block: blockN, warn: warnN, rows }, null, 2));
} else {
  console.log('===== 九步流硬门禁 loop-gate =====');
  console.log(`基准: since=${SINCE_DATE}  签名=${KEYWORD || '(未指定)'}  head=${HEAD || '(未指定)'}`);
  console.log('');
  for (const r of rows) {
    const mark = r.ok ? 'PASS' : (r.sev === 'block' ? 'BLOCK' : 'WARN ');
    console.log(`[${mark}] ${r.name}`);
    if (r.detail) console.log(`        ${r.detail}`);
  }
  console.log('--------------------------------------');
  console.log(`PASS=${passN} BLOCK=${blockN} WARN=${warnN}`);
  
// ── 门禁性质分类（2026-09-29 加；2026-09-30 晚改为只在 --human 显示）────
// 🔒 查实物（验产物/哈希/测试结果/事件流，AI 编不出来）:
//   ②-1 计划文档是本轮产出   ③-1 本轮有按模块 commit
//   ③-2 测试有结果且 0 失败  ③-4 契约回归真跑过（test-results 有 xml）
//   ④-2 verify.ps1 出过 VERIFIED
// 📝 查痕迹（只看「本轮有没有写这段带签名的话」→ 残余类，应尽量迁成事件断言）:
//   ① 档位自报  ②-2/②-3 外脑落盘  ②-4 查过经验库  ②-5 自问几问
//   ③-3 维护说明  ③-5 不回退清单对照  ④-1 事件证据  ④-3 效率监察
//   ④-4 巡检异常  ④-5 LLM 有脑留痕  ⑤-1/⑤-2 日志带签名  ⑤-3 数据清理  ⑥ 轮回点
// 👤 人工：06-检查清单四段（机器判不了）
//
// ★ 默认【不打印】—— AI 读得到门禁输出，标签等于给它一张
//   「哪些检查可以糊弄」的地图。加 --human 才给人看。
const HUMAN_FLAG = process.argv.includes("--human");
const REAL_ITEMS = ["②-1","③-1","③-2","③-4","④-2"];
if (HUMAN_FLAG) {
  console.log('性质：🔒 查实物 ' + REAL_ITEMS.length + ' 项 ｜ 📝 查痕迹 15 项 ｜ 👤 人工 1 项（06-检查清单）');
  console.log('  ★ 观察点：若「查痕迹」那 15 项每轮都秒过 = 它们只在走过场，不值得当门禁；反之若某项反复卡住 = 它在真干活，别削。');
}
console.log(`VERDICT=${verdict} ${blockN ? '← 有 BLOCK：不许收工' : '← 六步都有盘上证据'}`);
}
process.exit(blockN === 0 ? 0 : 1);
