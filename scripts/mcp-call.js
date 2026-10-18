#!/usr/bin/env node
/* ============================================================
   mcp-call.js —— 调游戏内 AI 的唯一正确姿势
   立于 2026-10-03，因为「needs a 'companion' argument」这个坑我踩了 4 次。

   ★ 三个坑都在这文件里，不要凭记忆写调用：
     ① companion 必须作为独立参数，不能只塞进 args（否则报 needs a 'companion' argument）
     ② 大段参数/中文参数用 @file（走文件，不过 shell 的引号与代码页）
     ③ 脚本目录路径必须用 fileURLToPath+dirname 拼，
        new URL('.', import.meta.url).pathname 会把空格变成 %20 ⇒ module not found

   用法：
     node mcp-call.js <tool> <companion> '<json-args>'
     node mcp-call.js <tool> <companion> @<file.json>
     node mcp-call.js <tool> <companion> key=value key2=value2
   例：
     node mcp-call.js get_self_status DDD
     node mcp-call.js enqueue DDD @q.json                 # q.json = {"text":"<query>…</query>"}
     node mcp-call.js acx_execute DDD ac_name=do_while_scan ac_version=1
     node mcp-call.js acx_resume DDD run_id=2e072823 input='{"min_hp":20}'
   ⚠️ 中文参数：不要用 PowerShell 的 ConvertTo-Json 写文件（会变乱码且吞掉 companion）。
      用编辑器/write 工具直接写 .json，或用 [char]0xXXXX 码点构造。
   ============================================================ */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { execFileSync } from 'node:child_process';

const [tool, companion, argsRaw] = process.argv.slice(2);
if (!tool) {
  console.log('usage: node mcp-call.js <tool> <companion> [json|@file|key=value ...]');
  process.exit(2);
}

/* ★ 坑①：companion 既要作为独立参数，也要在 args 里（numen-mcp.js:114 会把
   不以 '@' 或 '{' 开头的 a2 当成「裸同伴名」，那样整段参数就被丢掉了）。 */
const args = (() => {
  if (!argsRaw) return {};
  if (argsRaw.startsWith('@')) return JSON.parse(readFileSync(argsRaw.slice(1), 'utf8'));
  if (argsRaw.trim().startsWith('{')) return JSON.parse(argsRaw);
  const o = {};
  for (const kv of argsRaw.split(/\s+/).filter(Boolean)) {
    const i = kv.indexOf('=');
    if (i > 0) o[kv.slice(0, i)] = kv.slice(i + 1);
  }
  return o;
})();

const payload = { ...args, companion };

/* ★ 坑③：路径里的空格。 */
const HERE = dirname(fileURLToPath(import.meta.url));
process.stdout.write(
  execFileSync(
    process.execPath,
    [join(HERE, 'numen-mcp.js'), 'call', tool, JSON.stringify(payload)],
    { encoding: 'utf8', timeout: 60000 },
  ),
);