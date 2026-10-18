#!/usr/bin/env node
/**
 * w-probe.js — W 池主动探针（2026-09-30 新增）
 *
 * ## 为什么需要它
 *
 * 2026-09-30 实测踩坑：GLM 在浏览器里答对了，但 w-brain-drive.js 落盘的文件里
 * 存的是**上一轮的旧答案**。原因：脚本用「回复区长度连续 3 次不变」判定生成结束，
 * 新答案还没渲染时，旧答案（9 个字）就已经「稳定」了 → 判定结束 → 抓走旧的。
 *
 * 光看「登录态还在 / ok:true」是**发现不了这个问题的** —— 必须拿一个
 * 「只有这次才可能答对的」问题去问，然后核对答案里有没有那个标记。
 *
 * ## 怎么判定可用
 *
 * 给每家发一个带**唯一随机数**的问题：
 *     「只回复这串数字，不要任何其他内容：<nonce>」
 * 然后检查落盘文件的回复里**有没有这个 nonce**。
 *   有   → 抓的是这次的答案 → ✔ 可用
 *   没有 → 抓到旧的/空的    → ✘ 不可信（多半是抓取选择器坏了）
 *
 * 这个办法能同时抓出「抓到旧答案」和「答非所问」两类问题。
 *
 * 用法：
 *   node w-probe.js                 探 4 家（GLM/DeepSeek/Kimi/Qwen）
 *   node w-probe.js glm kimi        只探指定的
 *   node w-probe.js --timeout 300000
 */

'use strict';
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const SELF = __filename;
const DRIVE = path.join(__dirname, 'w-brain-drive.js');
const REPORT = path.join(__dirname, '..', 'report');

const ALL = ['glm', 'deepseek', 'kimi', 'qwen'];
const argv = process.argv.slice(2);
const TIMEOUT = (() => { const i = argv.indexOf('--timeout'); return i >= 0 ? parseInt(argv[i + 1], 10) : 240000; })();
const targets = argv.filter((a) => ALL.includes(a));
const list = targets.length ? targets : ALL;

/** 4 位随机数，够唯一又不长 */
function nonce() { return String(Math.floor(1000 + Math.random() * 9000)); }

function askOne(platform, question) {
  const t0 = Date.now();
  try {
    const out = execFileSync(process.execPath, [DRIVE, 'ask', platform, question], {
      encoding: 'utf8', timeout: TIMEOUT, maxBuffer: 8 * 1024 * 1024,
    });
    const secs = ((Date.now() - t0) / 1000).toFixed(1);
    let j = null;
    try { j = JSON.parse(out.replace(/^\uFEFF/, '')); } catch (e) { /* 非 JSON */ }
    return { secs, ok: j ? j.ok : false, text: (j && j.text) || '', file: (j && j.file) || '', err: j ? (j.err || '') : '输出不是 JSON' };
  } catch (e) {
    const secs = ((Date.now() - t0) / 1000).toFixed(1);
    return { secs, ok: false, text: '', file: '', err: '调用失败/超时: ' + String(e.message).split('\n')[0] };
  }
}

console.log('════ W 池主动探针（%s）', new Date().toISOString().slice(0, 19).replace('T', ' '));
console.log('每家问一个带唯一随机数的问题；答对 = 抓取没抓到旧的\n');

const rows = [];
for (const p of list) {
  const n = nonce();
  const q = `只回复这串数字，不要任何其他内容：${n}`;
  process.stdout.write(`  ${p.padEnd(9)} 问「${n}」… `);
  const r = askOne(p, q);
  const has = r.text.includes(n);
  const verdict = !r.ok ? '✘ 调用失败' : has ? '✔ 可用' : '✘ 答案里没有 ' + n + '（多半抓到旧的）';
  console.log(`${verdict}   ${r.secs}s   ${r.chars || ''}`);
  if (r.text) console.log(`      「${r.text.replace(/\s+/g, ' ').slice(0, 70)}」`);
  if (r.err) console.log(`      错误: ${r.err}`);
  rows.push({ platform: p, nonce: n, ok: r.ok, hasNonce: has, secs: Number(r.secs), chars: r.text.length, text: r.text.slice(0, 400), file: r.file, err: r.err });
}

const good = rows.filter((r) => r.ok && r.hasNonce).length;
console.log('\n────────────────────────────────────────');
rows.forEach((r) => console.log(`  ${(r.ok && r.hasNonce ? '✔' : '✘')} ${r.platform.padEnd(9)} ${String(r.secs).padStart(6)}s  ${r.chars}字`));
console.log(`\n可用 ${good}/${rows.length}`);

if (!fs.existsSync(REPORT)) fs.mkdirSync(REPORT, { recursive: true });
const out = path.join(REPORT, `w-probe-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}.json`);
fs.writeFileSync(out, JSON.stringify({ at: new Date().toISOString(), good, total: rows.length, rows }, null, 1), 'utf8');
console.log('探针记录: ' + out);
console.log(good === rows.length ? '★ 全部可用' : '★ 有不可信的（见上）');
process.exit(good === rows.length ? 0 : 1);
