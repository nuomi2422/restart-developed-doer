#!/usr/bin/env node
'use strict';
/*
 * check-runtime-deployment.js
 * 查「源码改了但没编译部署」—— 游戏里跑的还是旧版。
 *
 * 为什么需要：2026-10-01 出了个假完成 —— RL-19 在文档里写「已改」，
 *   但部署的 jar 比提交早 2 天多。**源码改了 ≠ 游戏里变了。**
 *   中间隔着：编译 → 部署 jar → 重启游戏。
 *
 * ★ 判据不能只看时间（第一版就错在这）：
 *   改了 6 个 *Test.java，main 一行没动 → jar 不需要重新部署。
 *   所以核心判据是「该模块最后一次**改 main 源码**的提交时间」，
 *   不是「最后一次提交时间」。
 *
 * 退出码：0 = 全部已部署 / 2 = 有源码领先于 jar / 3 = 环境错
 */

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const EXIT_OK = 0;
const EXIT_STALE = 2;
const EXIT_USAGE = 3;

// jar 构建发生在 commit 之后几分钟是正常的，30 分钟内不算「没部署」
const TOLERANCE_MS = 30 * 60 * 1000;

const DEFAULT_SRC = 'D:\\rdd\\workspace\\minecraft-numen';
// ★ 只扫 live（= gpt 实例 = D:\rdd\mc\The Best of Twilight Forest）。
//   用户 2026-10-01 确认：现在只用 live，instances\test 已不再使用 → 不计入债。
//   test 目录仍在磁盘上，要查就显式 --mods <路径> 或 --all-instances。
const DEFAULT_MODS = [
  'D:\\rdd\\mc\\The Best of Twilight Forest\\mods',   // ← live（唯一在用）
];
const TEST_MODS = 'D:\\rdd\\workspace\\rdd-selfcompile\\instances\\test\\mods';

/** jar 文件名主干 → git 源码目录（按依赖关系配对） */
const JAR_TO_MODULE = {
  'numen-plugin-rdd': { dirs: ['plugins/rdd', 'rdd-core'], label: 'RDD 插件(+rdd-core)' },
  'numen-plugin-selfcompile': { dirs: ['plugins/selfcompile'], label: '自编译插件' },
  'numen-plugin-learner': { dirs: ['plugins/learner'], label: '学习者插件' },
  'numen-plugin-acx': { dirs: ['plugins/acx'], label: 'ACX 插件' },
  'numen-plugin-experience': { dirs: ['plugins/experience'], label: '经验插件' },
  'numen_api': { dirs: ['api/common', 'ai'], label: 'NumenApi + ai' },
  'numen-neoforge': { dirs: ['core/common'], label: '模组本体' },
};

function git(repo, args) {
  try {
    return execFileSync('git', ['-C', repo, ...args], {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 120000,
    });
  } catch (e) { return ''; }
}

/** 该目录最后一次改 main 源码的提交时间（null = 从没改过 / 目录不存在） */
function lastMainChange(repo, dir) {
  // --name-only 拿文件列表，只认 src/main/java
  const raw = git(repo, ['log', '--format=%H|%ct', '--name-only', '-n', '30', '--', dir]);
  if (!raw) return null;
  let cur = null;
  for (const line of raw.split(/\r?\n/)) {
    const m = line.match(/^([0-9a-f]{40})\|(\d+)$/);
    if (m) { cur = { sha: m[1], ts: parseInt(m[2], 10) * 1000 }; continue; }
    if (!cur) continue;
    if (/src[\\/]main[\\/]java/.test(line)) return cur;   // 第一个动过 main 的提交
  }
  // 30 条里没出现 main 改动 → 再往前翻
  const raw2 = git(repo, ['log', '--format=%H|%ct', '--name-only', '-n', '200', '--', dir]);
  cur = null;
  for (const line of (raw2 || '').split(/\r?\n/)) {
    const m = line.match(/^([0-9a-f]{40})\|(\d+)$/);
    if (m) { cur = { sha: m[1], ts: parseInt(m[2], 10) * 1000 }; continue; }
    if (!cur) continue;
    if (/src[\\/]main[\\/]java/.test(line)) return cur;
  }
  return null;
}

function fmt(ts) {
  const d = new Date(ts);
  const p = n => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function main() {
  const args = process.argv.slice(2);
  let src = DEFAULT_SRC, modsDirs = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--src') src = args[++i];
    else if (args[i] === '--mods') modsDirs.push(args[++i]);
    else if (args[i] === '--help' || args[i] === '-h') {
      console.log(`用法：node check-runtime-deployment.js [--src <minecraft-numen 路径>] [--mods <实例 mods 目录>]

  --src    Java 源码仓库（默认 minecraft-numen）
  --mods   实例 mods 目录，可重复。默认只扫 live（唯一在用）

判据：该 jar 对应模块「最后一次改 main 源码的提交时间」 vs jar 的 LastWriteTime
  jar 更早 → 源码领先运行时，游戏里跑的是旧版
  只改测试（main 一行没动）→ 不算债

退出码：0 全部已部署 / 2 有源码领先于 jar / 3 环境错`);
      process.exit(EXIT_OK);
    }
  }
  if (!fs.existsSync(src)) { console.error(`源码仓库不存在: ${src}`); process.exit(EXIT_USAGE); }
  if (!modsDirs.length) modsDirs = DEFAULT_MODS.filter(d => fs.existsSync(d));
  if (!modsDirs.length) { console.error('没找到任何 mods 目录，用 --mods 指定'); process.exit(EXIT_USAGE); }

  // 预取所有模块的「最后改 main 时间」
  const modules = {};
  for (const [jar, info] of Object.entries(JAR_TO_MODULE)) {
    let latest = null;
    for (const d of info.dirs) {
      const c = lastMainChange(src, d);
      if (c && (!latest || c.ts > latest.ts)) latest = { ...c, dir: d };
    }
    modules[jar] = { ...info, latest };
  }

  const stale = [];
  const rows = [];

  console.log('');
  console.log('════ 运行时部署检查（源码改了 vs jar 部署了没）════');
  console.log(`源码仓库 : ${src}`);
  console.log(`判据     : 该模块最后一次改 **src/main/java** 的提交时间 vs jar LastWriteTime`);
  console.log(`           （只改测试 → 不算债；这是第一版的错判点）`);
  console.log('');

  for (const modsDir of modsDirs) {
    const tag = modsDir.includes('instances\\test') ? 'test 实例' : 'live';
    console.log(`── ${tag}：${modsDir}`);
    const files = fs.readdirSync(modsDir).filter(f => f.startsWith('numen') && f.endsWith('.jar'));
    if (!files.length) { console.log('   （无 numen jar）\n'); continue; }

    for (const f of files.sort()) {
      const jarKey = Object.keys(JAR_TO_MODULE).find(k => f.startsWith(k));
      if (!jarKey) continue;
      const full = path.join(modsDir, f);
      const jarTs = fs.statSync(full).mtimeMs;
      const m = modules[jarKey];
      const srcTs = m.latest ? m.latest.ts : null;

      let verdict, detail;
      if (!srcTs) {
        verdict = '?';
        detail = '源码仓库里没找到这个模块（模块映射可能过期）';
      } else if (jarTs + TOLERANCE_MS >= srcTs) {
        // 容差 30 分钟：git 提交时间戳取自 commit 时刻，jar 构建发生在其后，
        // 差几分钟是正常的。不设容差会满屏「领先 0 小时」的假警报。
        verdict = 'OK';
        const h = Math.round((jarTs - srcTs) / 3600000);
        detail = h >= 1 ? `jar 比源码还新 ${h}h` : 'jar 已包含最新 main 改动';
      } else {
        const gapH = (srcTs - jarTs) / 3600000;
        verdict = 'STALE';
        detail = gapH >= 1
          ? `★ 源码领先 **${Math.round(gapH)} 小时**（最后改 main：${m.latest.sha.slice(0, 7)} @ ${fmt(srcTs)}，在 ${m.latest.dir}）`
          : `★ 源码领先 **${Math.round(gapH * 60)} 分钟**（最后改 main：${m.latest.sha.slice(0, 7)} @ ${fmt(srcTs)}，在 ${m.latest.dir}）`;
        stale.push({ modsDir, tag, jar: f, gapH, detail, label: m.label });
      }
      rows.push({ tag, jar: f, jarTs, srcTs, verdict, detail, label: m.label, module: m.latest ? m.latest.dir : '?' });
      const mark = verdict === 'OK' ? '✔' : verdict === 'STALE' ? '✘' : '?';
      console.log(`   ${mark} ${f.padEnd(48)} jar ${fmt(jarTs)}   ${detail}`);
    }
    console.log('');
  }

  console.log('─'.repeat(70));
  if (!stale.length) {
    console.log('VERDICT=DEPLOYED  所有模块的 main 改动都已进 jar');
    console.log('');
    console.log('⚠️ 这只证明「jar 是新的」，不证明「游戏在跑这个 jar」——');
    console.log('   还要确认：① 游戏启动过（jar 改了要重启才生效）');
    console.log('            ② 加载的是这个目录（不是别的实例）');
    console.log('   要更强的话：解包 jar 找新写的特征字符串，见说明文档「怎么验真的生效了」。');
    process.exit(EXIT_OK);
  }

  console.log(`VERDICT=STALE  ${stale.length} 个 jar 的源码领先于部署：`);
  for (const s of stale) {
    console.log(`   · [${s.tag}] ${s.jar}`);
    console.log(`     ${s.detail}`);
    console.log(`     修：run-mutation.ps1 -Requirement "<部署 XX>" -Module <模块>  → 重启游戏`);
  }
  console.log('');
  console.log('★ 这就是「假完成」的形态：源码改了、测试绿了、文档写了「已改」，');
  console.log('  但游戏里跑的还是旧版 —— 用户看到的是没生效的行为。');
  process.exit(EXIT_STALE);
}

main();