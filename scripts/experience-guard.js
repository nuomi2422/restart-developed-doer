#!/usr/bin/env node
/**
 * experience-guard.js — 经验库写入闸（2026-09-29 立，因为真出过事）
 *
 * ## 为什么有它
 *
 * 本会话用 `JSON.parse(file)` 读一个 JSONL 经验的 JSON 数组文件，
 * 解析失败时 fall back 成 `[]`，然后把种子条目写回 —— **静默覆盖了
 * 原有 3 条经验（含一条 VERIFIED 级「附魔台 GUI 按钮无法点击」）**。
 * 当时的表现是脚本打印 "0 -> 5 (+5)"，**看起来像成功**。
 *
 * 这和「编译成功≠行为成功」是同一类错误：**写入方自认为成功，
 * 数据已经没了。** 所以写入必须过闸。
 *
 * ## 规则（任一不满足 → 拒绝写）
 *
 *   1. 解析失败/类型不符 → 拒绝（绝不当空库处理）
 *   2. 写入后条目数减少 → 拒绝并还原
 *   3. 写入前文件存在但无法解析 → 拒绝
 *   4. 只能 append（按 stableKey 合并），不提供整体覆盖
 *
 * 用法：
 *   node experience-guard.js --check   <file...>      只体检，不改
 *   node experience-guard.js --append  <file> --json <entries.json>
 *   node experience-guard.js --backup-and-append <file> --json <entries.json>
 */

'use strict';
const fs = require('fs');
const path = require('path');

const argv = process.argv.slice(2);
const flag = n => argv.includes('--' + n);
// Positional args are the ones NOT consumed as a --flag's value. The previous
// version used a fragile "previous arg starts with --" heuristic, which silently
// dropped positional file paths and made --check report nothing. Parse properly.
const VALUED = new Set(['json']);
const positional = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a.startsWith('--')) {
    if (VALUED.has(a.slice(2)) && i + 1 < argv.length) i++;   // skip its value
    continue;
  }
  positional.push(a);
}
const files = positional;
const val = n => { const i = argv.indexOf('--' + n); return i < 0 ? null : argv[i + 1]; };

function readStore(p) {
  const raw = fs.readFileSync(p, 'utf8');
  if (!raw.trim()) return { list: [], ok: true, note: 'empty file' };
  try {
    const j = JSON.parse(raw);
    if (!Array.isArray(j)) return { list: null, ok: false, note: 'top-level JSON is not an array' };
    return { list: j, ok: true, note: 'parsed ' + j.length + ' entries' };
  } catch (e) {
    return { list: null, ok: false, note: 'parse error: ' + e.message.slice(0, 80) };
  }
}

function inspect(p) {
  if (!fs.existsSync(p)) return { exists: false };
  const { list, ok, note } = readStore(p);
  return { exists: true, ok, count: Array.isArray(list) ? list.length : -1, note, list };
}

if (flag('check')) {
  let bad = 0;
  for (const f of files) {
    const r = inspect(f);
    if (!r.exists) { console.log('  [ABSENT] ' + path.basename(f)); continue; }
    const mark = r.ok ? '  [ok]   ' : '  [BAD]  ';
    if (!r.ok) bad++;
    console.log(mark + path.basename(f) + '  entries=' + r.count + '  ' + r.note);
  }
  console.log(bad === 0 ? '\nALL STORES PARSEABLE' : '\n' + bad + ' STORE(S) UNPARSEABLE — do NOT write to them');
  process.exit(bad === 0 ? 0 : 1);
}

const target = files[0];
const jsonArg = val('json');
if (!target || !jsonArg) { console.log('usage: experience-guard.js --append|--backup-and-append <file> --json <file>'); process.exit(2); }

// --- Gate before writing ---
const before = inspect(target);
if (before.exists && !before.ok) {
  console.error('REFUSED: ' + path.basename(target) + ' does not parse as a JSON array (' + before.note + ')');
  console.error('        Refusing to treat an unreadable store as empty — that is how data was lost on 2026-09-29.');
  process.exit(1);
}
const incoming = JSON.parse(fs.readFileSync(jsonArg, 'utf8'));
if (!Array.isArray(incoming)) { console.error('REFUSED: --json payload is not an array'); process.exit(1); }

// --- Backup before any mutation (this alone would have saved the 2026-09-29 loss) ---
let backupPath = null;
if (flag('backup-and-append') && before.exists) {
  backupPath = target + '.pre-append-' + Date.now() + '.bak';
  fs.copyFileSync(target, backupPath);
  console.log('backup -> ' + path.basename(backupPath));
}

// --- Append-only merge by stable key ---
const byId = new Map((before.list || []).map(e => [e.id, e]));
const beforeIds = new Set(byId.keys());
let added = 0;
for (const e of incoming) {
  if (!e || typeof e.id !== 'string' || !e.id.trim()) {
    console.error('REFUSED: incoming entry without a stable id — cannot dedupe safely');
    process.exit(1);
  }
  if (!byId.has(e.id)) { byId.set(e.id, e); added++; }
}
const merged = [...byId.values()];

// --- Gate on the RESULT: never fewer entries than we started with ---
if (merged.length < (before.list ? before.list.length : 0)) {
  console.error('REFUSED: merge would lose entries (' + (before.list ? before.list.length : 0) + ' -> ' + merged.length + ')');
  if (backupPath) fs.copyFileSync(backupPath, target);
  process.exit(1);
}

fs.writeFileSync(target, JSON.stringify(merged), 'utf8');
console.log(path.basename(target) + ': ' + (before.list ? before.list.length : 0) + ' -> ' + merged.length + ' (+' + added + ' added, ' + (merged.length - added) + ' existing kept)');
if (beforeIds.size) console.log('  preserved keys: ' + [...beforeIds].length);
process.exit(0);
