#!/usr/bin/env node
/**
 * check-doc-freshness.js —— 文档时效门禁
 *
 * 解决的问题（2026-10-03 实测事故）：
 *   `61-任务链功能线全景与回归固化方案` 同一天里，§二 是「T1–T9 动工前的快照」、
 *   §八 是「T1–T9 已实施」。只读 §二 就得出「9 条红线改了不会有任何测试变红」——
 *   而实际上那天已经有 7 个 PinTest / 154 项落地。**根因不是记错，是文档没有「核对时点」这个维度。**
 *
 * 它查五件事（对应五种真实的过期形态）：
 *   ① MISSING_FILE    台账登记了但文件不在了（被删/被改名没同步）
 *   ② UNREGISTERED    目录里有文档但台账没登记（新增文档忘了进机制 = 机制立刻失效）
 *   ③ NEVER_VERIFIED  登记了但 lastVerified 为空（默认不阻断；--strict 才阻断）
 *   ④ OVERDUE         距上次核对超过 ttlDays（该复核了）
 *   ⑤ STALE_EDIT      ★ 最后核对之后这个文档又被改过 —— 该条核对自动作废
 *   ⑥ DUP_NUMBER      ★ 两份文档用了同一个编号（本工作区并行会话常态：
 *                        2026-10-03 我建了「64-未接线审计」，同时并行会话建了「64-两条数据流总览」）
 *                      编号是这套文档唯一的排序/引用键，撞号 = 引用指向不明
 *
 * 用法：
 *   node check-doc-freshness.js            # 常规：②④⑤⑥ 阻断
 *   node check-doc-freshness.js --strict   # 连「从未核对」也阻断
 *   node check-doc-freshness.js --json     # 机器可读
 *
 * 退出码：0 = 全过 ｜ 2 = 有阻断项 ｜ 3 = 台账/目录读不到（环境问题，不是债）
 */

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const DOCS_ROOT = 'D:\\rdd\\docs\\rdd-docs';
const LEDGER_REL = 'rdd架构/自编译统一版/docs-freshness.json';
const LEDGER = path.join(DOCS_ROOT, 'rdd架构', '自编译统一版', 'docs-freshness.json');
const DIR = path.join(DOCS_ROOT, 'rdd架构', '自编译统一版');

const argv = process.argv.slice(2);
const STRICT = argv.includes('--strict');
const AS_JSON = argv.includes('--json');
const TODAY = new Date();
const dayMs = 86400000;

function ymd(d) {
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}
function daysBetween(fromIso, to) {
  const [y, m, d] = fromIso.split('-').map(Number);
  return Math.round((to - new Date(y, m - 1, d)) / dayMs);
}

function lastGitDate(relPath) {
  try {
    const out = execFileSync('git', ['-C', DOCS_ROOT, 'log', '-1', '--format=%as', '--', relPath],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    return out.trim() || null;
  } catch (e) { return null; }
}

// ── 读台账 ────────────────────────────────────────────────────────
if (!fs.existsSync(LEDGER)) {
  console.error('FATAL: 读不到台账 ' + LEDGER + '（exit 3 = 环境问题，不是债）');
  process.exit(3);
}
let ledger;
try {
  ledger = JSON.parse(fs.readFileSync(LEDGER, 'utf8'));
} catch (e) {
  console.error('FATAL: 台账不是合法 JSON：' + LEDGER + ' → ' + e.message + '（exit 3）');
  process.exit(3);
}

const findings = [];
const add = (level, file, code, msg) => findings.push({ level, file, code, msg });

// ② 目录里未登记的文档（.bak 不算）
const onDisk = fs.readdirSync(DIR).filter(f => f.endsWith('.md') && !f.endsWith('.bak'));
const registered = new Set((ledger.docs || []).map(d => d.file));
for (const f of onDisk) {
  if (!registered.has(f)) add('BLOCK', f, 'UNREGISTERED', '目录里有这份文档，但台账没登记 —— 新增文档必须进台账，否则机制对它无效');
}
for (const d of ledger.docs || []) {
  registered.delete(d.file);
}
for (const f of registered) {
  add('BLOCK', f, 'MISSING_FILE', '台账登记了，但目录里找不到这个文件（被删或被改名没同步）');
}

// ⑥ 编号撞号（README / _ 开头 / 无编号的辅助文件不参与）
// 台账里 knownDuplicateNumbers 登记的是**已知旧撞号**（别人建档时没查号留下的债）：
// 它们降级为 WARN，新撞号仍然 BLOCK —— 否则一次旧债会把门禁永久钉死，门禁一旦被长期忽略就等于没有。
const KNOWN_DUP = new Set(ledger.knownDuplicateNumbers || []);
const byNumber = new Map();
for (const f of onDisk) {
  const m = f.match(/^(\d{2})-/);
  if (!m) continue;
  if (!byNumber.has(m[1])) byNumber.set(m[1], []);
  byNumber.get(m[1]).push(f);
}
for (const [num, list] of byNumber) {
  if (list.length > 1) {
    const known = KNOWN_DUP.has(num);
    add(known ? 'WARN' : 'BLOCK', list.join(' + '), 'DUP_NUMBER',
        (known ? '[已知旧撞号 · 已登记为债] ' : '')
        + '★ 编号 ' + num + ' 被 ' + list.length + ' 份文档同时使用：' + list.join(' / ')
        + ' —— 编号是这套文档唯一的排序与引用键，撞号会让「见 ' + num + ' §三」指向不明。'
        + (known ? '处置：改名会打断别处的引用，需与引用方一起改，故先登记为债。'
                 : '新文档建档前必须先确认这个号没被占（本工作区并行会话常态）。'));
  }
}

// ③④⑤ 逐份核对
const rows = [];
for (const d of ledger.docs || []) {
  const full = path.join(DIR, d.file);
  if (!fs.existsSync(full)) continue; // 已在上面报 MISSING_FILE
  const rel = LEDGER_REL.replace('docs-freshness.json', d.file);
  const git = lastGitDate(rel);
  let mtime = null;
  try {
    const [hh, mm, ss] = fs.statSync(full).mtime.toISOString().slice(0, 19).split('T')[1].split(':');
    mtime = ymd(new Date(Date.UTC(2000, 0, 1, +hh - 8, +mm, +ss)));
  } catch (e) { /* ignore */ }

  let status, age = null, over = null;
  if (!d.lastVerified) {
    status = 'NEVER_VERIFIED';
    add(STRICT ? 'BLOCK' : 'WARN', d.file, 'NEVER_VERIFIED',
        '从未核对过 —— 没有 lastVerified，读者分不清「这是现状」还是「这是某天的快照」');
  } else {
    age = daysBetween(d.lastVerified, TODAY);
    over = age - (d.ttlDays || 14);
    if (over > 0) {
      status = 'OVERDUE';
      add('BLOCK', d.file, 'OVERDUE',
          '距上次核对 ' + age + ' 天 > ttl ' + (d.ttlDays || 14) + ' 天（超期 ' + over + ' 天）');
    } else {
      // ★ 核对之后又被改过 → 该条核对作废
      const editedAfter = [];
      if (git && git > d.lastVerified) editedAfter.push('git ' + git);
      if (mtime && mtime > d.lastVerified) editedAfter.push('mtime ' + mtime);
      if (editedAfter.length) {
        status = 'STALE_EDIT';
        add('BLOCK', d.file, 'STALE_EDIT',
            '★ 核对（' + d.lastVerified + '）之后这个文档又被改过（' + editedAfter.join(' / ')
            + '）—— 那次核对的结论不再覆盖当前内容，必须重核');
      } else {
        status = 'OK';
      }
    }
  }
  rows.push({ file: d.file, role: d.role, ttl: d.ttlDays, lastVerified: d.lastVerified, age, status, git });
}

// ── 输出 ──────────────────────────────────────────────────────────
const cnt = (c) => findings.filter(f => f.code === c).length;
const summary = {
  total: (ledger.docs || []).length,
  ok: rows.filter(r => r.status === 'OK').length,
  neverVerified: cnt('NEVER_VERIFIED'),
  overdue: cnt('OVERDUE'),
  staleEdit: cnt('STALE_EDIT'),
  unregistered: cnt('UNREGISTERED'),
  missingFile: cnt('MISSING_FILE'),
  dupNumber: cnt('DUP_NUMBER'),
  mode: STRICT ? 'strict' : 'normal',
  today: ymd(TODAY),
};

if (AS_JSON) {
  console.log(JSON.stringify({ summary, findings, rows }, null, 2));
} else {
  const L = [];
  L.push('文档时效门禁 · ' + summary.today + ' · 模式 ' + summary.mode);
  L.push('台账 ' + LEDGER);
  L.push('');
  L.push('总数 ' + summary.total + ' ｜ 已核对且未过期 ' + summary.ok
    + ' ｜ 从未核对 ' + summary.neverVerified + ' ｜ 超期 ' + summary.overdue
    + ' ｜ 核对后又被改 ' + summary.staleEdit
    + ' ｜ 未登记 ' + summary.unregistered + ' ｜ 台账指向的文件丢失 ' + summary.missingFile
    + ' ｜ 编号撞号 ' + summary.dupNumber);
  L.push('');
  const order = { BLOCK: 0, WARN: 1 };
  for (const f of findings.slice().sort((a, b) => order[a.level] - order[b.level] || a.file.localeCompare(b.file))) {
    L.push('[' + f.level + '] ' + f.file + '  ' + f.code + ' — ' + f.msg);
  }
  L.push('');
  L.push('—— 逐份明细（按状态）——');
  const rank = { STALE_EDIT: 0, OVERDUE: 1, NEVER_VERIFIED: 2, OK: 3 };
  for (const r of rows.slice().sort((a, b) => rank[a.status] - rank[b.status] || a.file.localeCompare(b.file))) {
    if (r.status === 'OK') continue;
    L.push('  ' + r.status.padEnd(16) + ' ' + String(r.lastVerified || '—').padEnd(12)
      + ' ttl=' + String(r.ttl).padEnd(4) + ' ' + r.file);
  }
  const okList = rows.filter(r => r.status === 'OK');
  if (okList.length) {
    L.push('');
    L.push('—— 合格（这些是当前唯一可以当依据的）——');
    for (const r of okList) L.push('  OK  ' + r.lastVerified + '  ' + r.file);
  }
  console.log(L.join('\n'));
}

const blocked = findings.some(f => f.level === 'BLOCK');
process.exit(blocked ? 2 : 0);
