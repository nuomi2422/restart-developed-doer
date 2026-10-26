#!/usr/bin/env node
// check-jar-lang.js -- 检查部署到 live 的 numen_api jar 是否带 datagen 语言文件。
//
// 为什么需要它（TL-2026-10-06-01，静默失效类）：
//   翻译键全在 ModLanguageData（源码里），但 lang JSON 是 :api:neoforge:runData 的产物，
//   落在 api/neoforge/src/generated/resources/（被 .gitignore 忽略、由 sourceSets 带进构建）。
//   本地 run-mutation 构建不跑 datagen —— 一旦生成目录被删/被清，dev jar 就静默缺翻译，
//   游戏里所有 I18n 键显示成原始键（numen.xxx），无任何报错。
//
// 原理：jar 就是 zip，zip 的本地文件头/中央目录里存着明文字符串文件名，
//   直接在字节流里搜 "assets/numen_api/lang/zh_cn.json" 即可，无需解压依赖。
//
// usage:
//   node check-jar-lang.js                  # 默认查 live 实例 mods 下的 numen_api jar
//   node check-jar-lang.js --dir <mods目录> # 指定 mods 目录
//   node check-jar-lang.js --jar <文件>     # 直接指定 jar
//   node check-jar-lang.js --json           # 机器可读输出
//
// exit: 0 = 两个语言文件都在；1 = 缺（部署 jar 没带翻译）；3 = 环境错（目录/jar 找不到）

import fs from 'node:fs';
import path from 'node:path';

const LIVE_MODS = 'D:\\rdd\\mc\\The Best of Twilight Forest\\mods';
const WANTED = ['assets/numen_api/lang/en_us.json', 'assets/numen_api/lang/zh_cn.json'];

function parseArgs(argv) {
  const a = { dir: LIVE_MODS, jar: null, json: false };
  for (let i = 2; i < argv.length; i++) {
    const t = argv[i];
    if (t === '--dir') a.dir = argv[++i];
    else if (t === '--jar') a.jar = argv[++i];
    else if (t === '--json') a.json = true;
    else if (t === '--help' || t === '-h') { console.log(fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').slice(0, 17).join('\n')); process.exit(0); }
    else { console.error('unknown arg: ' + t); process.exit(3); }
  }
  return a;
}

function findJar(args) {
  if (args.jar) return fs.existsSync(args.jar) ? args.jar : null;
  if (!fs.existsSync(args.dir)) return null;
  const hit = fs.readdirSync(args.dir).filter(f => /^numen_api-neoforge-.*\.jar$/.test(f));
  if (!hit.length) return null;
  return path.join(args.dir, hit[0]);
}

const args = parseArgs(process.argv);
const jar = findJar(args);
if (!jar) {
  const msg = { ok: false, state: 'ENV', detail: '未找到 numen_api jar（dir=' + args.dir + '）' };
  if (args.json) console.log(JSON.stringify(msg)); else console.error('[check-jar-lang] 环境错: ' + msg.detail);
  process.exit(3);
}

const buf = fs.readFileSync(jar);
const present = {};
for (const w of WANTED) present[w] = buf.includes(Buffer.from(w));
const ok = WANTED.every(w => present[w]);
const stat = fs.statSync(jar);
const out = {
  ok,
  state: ok ? 'OK' : 'MISSING_LANG',
  jar,
  jarMtime: stat.mtime.toISOString(),
  jarBytes: stat.size,
  entries: present,
  hint: ok ? 'lang 已在 jar 内（游戏内 I18n 键正常显示）'
           : 'jar 缺 lang JSON → 先跑 :api:neoforge:runData 再重新构建部署（见 TL-2026-10-06-01）',
};
if (args.json) {
  console.log(JSON.stringify(out, null, 2));
} else {
  console.log('[check-jar-lang] ' + (ok ? 'OK' : '缺翻译!') + ' ' + jar);
  for (const w of WANTED) console.log('  ' + (present[w] ? '有' : '缺') + ' ' + w);
  if (!ok) console.log('  提示: ' + out.hint);
}
process.exit(ok ? 0 : 1);
