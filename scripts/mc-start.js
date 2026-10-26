#!/usr/bin/env node
/**
 * mc-start.js — 起 Minecraft 的唯一入口（2026-10-01）
 *
 * 为什么要重写：把 11 条踩过的坑一次性封死，让调用方只填一个参数、不可能卡住。
 *
 * 用法（只有一个位置参数）：
 *     node mc-start.js                      # 默认实例 gpt，默认 120 分钟（2 小时）
 *     node mc-start.js gpt                  # 指定实例
 *     node mc-start.js gpt 60               # 实例 + 运行时长（分钟）
 *     node mc-start.js gpt 60 --status      # 只查状态，秒回
 *     node mc-start.js gpt 60 --stop        # 关掉
 *     node mc-start.js gpt 120 --wait 0     # 只脱离启动，不轮询
 *
 * ★ 本脚本封死的 11 个坑（都来自 memory 图谱，见 entities 名）
 *  1 issue/sync-wait-on-mc-launch-hangs-shell
 *     直接跑 launch-mc.ps1 等它返回 → shell 卡死（子进程继承句柄不释放）
 *     ★ 用 spawn(detached)+unref，父进程立刻返回，句柄不继承
 *  2 同上 —— 加长 timeout 不是解法（给 600s 就实打实等 600s）
 *  3 pitfall/ps51-arg-passing-two-traps（陷阱A）
 *     Start-Process 的 -File 路径含空格不给引号 → 参数被拆开、脚本从没跑、
 *     外面只看到 java 进程一直是 0，极易误判成 OOM
 *  4 issue/powershell-array-splat-is-positional-not-named
 *     @args 数组 splat 是按位置传参 → Instance='-Instance'、AutoStopMinutes='gpt'
 *     → 参数转换失败 → 游戏从没启动 → 又误判成 OOM
 *     ★ 本脚本用哈希表 splat（在 -Command 的 script block 里）
 *  5 pitfall-mc-instance-must-be-derived-not-typed
 *     查实例和启动实例写成两个（查 test 起 gpt）→ ★ 本脚本只认一个名字
 *  6 同上 —— launch-mc.ps1 的 fallback 还是 'test'，config.ps1 默认 gpt，两处不一致
 *     ★ 本脚本不读任何 fallback
 *  7 mc-autostop-default-violated-iron-rule
 *     2026-10-02 用户定：默认 120（2 小时），旧的「在场 ≤10 分钟」铁律作废
 *     → ★ 本脚本默认 120
 *  8 issue/sync-wait-on-mc-launch-hangs-shell
 *     已经在跑还去启动 → 撞单实例锁，把先起的实例弄丢
 *     ★ 本脚本起之前先查，起了就只报状态不动手
 *  9 pitfall-mc-dies-mid-load-means-oom-not-bug
 *     内存不足时 MC 加载中途消失，看起来像 mod 崩
 *     ★ 本脚本起之前先查空闲内存，<1200MB 直接拒起并说清原因
 * 10 issue/shell-hangs-after-minecraft-exit
 *     判据只看脚本退出码 → 退出码 0 不等于游戏起来了
 *     ★ 只看「实际发生」：java / log 增长 / session.lock / MCP 端口
 * 11 pitfall/copy-whole-libs-dir-into-mods
 *     build\libs\*.jar 整目录拷进 mods（库 jar 被当 mod 组，游戏卡在加载界面）
 *     ★ 本脚本不碰 mods，只调既有部署脚本
 *
 * ★ 判据：只看「实际发生」，不看退出码
 *     ① java 进程在    ② latest.log 在增长    ③ session.lock 时间更新
 *     ④ 日志出现 [rdd] / [numen-llm] 行      ⑤ MCP 8765 可连
 */
'use strict';
const { execFileSync } = require('child_process');
const fs = require('fs');

const ROOT = 'D:/rdd/workspace';
const SCRIPTS = ROOT + '/rdd-selfcompile/scripts';
const LAUNCH = SCRIPTS + '/launch-mc.ps1';
const PS = 'powershell.exe';
const GAME = 'D:/rdd/mc/The Best of Twilight Forest';

const ARGV = process.argv.slice(2);
const POS = ARGV.filter((a) => !a.startsWith('--'));
const FLAGS = ARGV.filter((a) => a.startsWith('--'));
const INSTANCE = POS[0] || 'gpt';
const MINUTES = POS[1] ? parseInt(POS[1], 10) : 120;
const WANT_STATUS = FLAGS.includes('--status');
const WANT_STOP = FLAGS.includes('--stop');
const WI = FLAGS.indexOf('--wait');
const WAIT = WI >= 0 ? parseInt(FLAGS[WI + 1], 10) : 90;

function psCmd(c, ms) {
  return execFileSync(PS, ['-NoProfile', '-Command', c], { encoding: 'utf8', timeout: ms || 30000 }).trim();
}
function num(c) { return parseInt(psCmd(c), 10) || 0; }

function probe() {
  return {
    java: num('(Get-Process java,javaw -ErrorAction SilentlyContinue | Measure-Object).Count'),
    log: num('(Get-Item "' + GAME + '/logs/latest.log" -EA SilentlyContinue).Length'),
    lock: psCmd('(Get-Item "' + GAME + '/saves/rdd/session.lock" -EA SilentlyContinue).LastWriteTime.ToString("HH:mm:ss")'),
    mcp: num('(Get-NetTCPConnection -State Listen -LocalPort 8765 -EA SilentlyContinue | Measure-Object).Count'),
    // ★ 2026-10-01 修：FreePhysicalMemory 单位是 KB，原来除以 1MB(1048576)
    //   把 2149MB 算成 "2"，导致 <1200MB 的门永远为真 → 这个启动门一直是死的。
    //   正确换算是 KB→MB 除 1024。备份：mc-start.js.pre-unitfix-20261001.bak
    mem: num('[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)'),
  };
}
function show(p, tag) {
  console.log('  [' + tag + '] java=' + p.java + ' log=' + p.log + 'B lock=' + p.lock +
    ' mcp=' + p.mcp + ' 空闲=' + p.mem + 'MB');
}

async function main() {
  if (!fs.existsSync(LAUNCH)) { console.error('[X] 找不到 ' + LAUNCH); process.exit(2); }

  // ---- --status：秒回 ----
  if (WANT_STATUS) {
    console.log('Minecraft 状态 @ ' + new Date().toLocaleTimeString());
    show(probe(), '现状');
    process.exit(0);
  }

  // ---- --stop ----
  if (WANT_STOP) {
    console.log('关 Minecraft（实例 ' + INSTANCE + '）…');
    try {
      execFileSync(PS, ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        '"' + SCRIPTS + '/mc-instance.ps1"', '-Action', 'Stop', '-Instance', INSTANCE],
        { encoding: 'utf8', timeout: 60000 });
      console.log('✔ 已关（或本来就没开）');
      process.exit(0);
    } catch (e) {
      console.error('[X] 关失败: ' + String(e.message).split('\n')[0]);
      process.exit(1);
    }
  }

  // ---- 起 ----
  let p = probe();
  console.log('起之前：');
  show(p, '现状');

  // 坑8：已经在跑就别再起
  if (p.java > 0 && p.mcp > 0) {
    console.log('');
    console.log('✔ 游戏已经在跑 —— 不重复启动（重起会撞单实例锁，把先起的实例弄丢）。');
    console.log('  要重开： node mc-start.js ' + INSTANCE + ' ' + MINUTES + ' --stop');
    process.exit(0);
  }

  // 2026-10-02 用户定：低内存也照起，不再硬拦（原来的 1200MB 硬门已去掉）
  // 只留一句提示，方便事后看日志时知道当时内存是紧的。
  if (p.mem > 0 && p.mem < 1200) {
    console.log('');
    console.log('[提醒] 空闲内存只有 ' + p.mem + 'MB（偏低）—— 照样起。');
    console.log('       万一中途消失，先看是不是被系统换出；这不等于 mod 崩。');
  }

  console.log('');
  console.log('启动 ' + INSTANCE + '（' + MINUTES + ' 分钟后自动关）…');

  // ★★ 2026-10-07 修（AI 实测踩坑）：**detached spawn 在 opencode 的 Bash 工具下活不过调用方**。
  //   工具按【进程树】收尾，detached+unref 的子进程一样被连坐杀掉 ——
  //   现象：mc-start.js / Start-Process / 前台 launch-mc 都说"已启动"，命令一结束游戏就没了
  //   （latest.log 不长、8765 不开、java 进程不见）。长 timeout 不是解法。
  //   改用 **WMI 创建进程**：新进程挂在 WmiPrvSE.exe 下、不在调用方的 job 对象里 → 活得下来。
  //   实测：WMI 创建的进程仍在 **session 1（交互会话）**，游戏窗口正常可见（不是 session 0 黑洞）。
  //   兜底办法（万一 WMI 不可用）：前台跑 launch-mc.ps1 并把输出重定向到文件
  //   （`... *> out.txt`）——游戏能起，但调用方可能仍被它的进程树拖住/收掉。
  const inner = PS + ' -NoProfile -ExecutionPolicy Bypass -File "' + LAUNCH +
    '" -Instance ' + INSTANCE + ' -AutoStopMinutes ' + MINUTES;
  const psCreate =
    '(Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = \'' +
    inner + '\' }).ProcessId';
  let launchedPid = '?';
  try {
    launchedPid = execFileSync(PS, ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', psCreate],
      { encoding: 'utf8', timeout: 30000 }).trim();
  } catch (e) {
    console.error('[X] WMI 创建启动进程失败: ' + String(e && e.message ? e.message : e).split('\n')[0]);
    process.exit(1);
  }
  console.log('  已脱离启动（WMI pid ' + launchedPid + '，session 1）—— 本命令立即返回，不会被调用方连坐。');

  if (WAIT <= 0) {
    console.log('  --wait 0 → 不轮询。查状态： node mc-start.js ' + INSTANCE + ' --status');
    process.exit(0);
  }

  // 坑10：轮询「实际发生」，不看退出码
  console.log('');
  console.log('轮询起没起来（最多 ' + WAIT + ' 秒）…');
  let lastLog = p.log, ok = 0;
  for (let i = 0; i < Math.ceil(WAIT / 3); i++) {
    await new Promise((r) => setTimeout(r, 3000));
    const q = probe();
    const grew = q.log > lastLog;
    show(q, String((i + 1) * 3).padStart(4) + 's');
    if (q.java > 0 && q.mcp > 0) ok++; else ok = 0;
    if (ok >= 2) {
      console.log('');
      console.log('✔ 起好了。 java=' + q.java + ' mcp8765=' + q.mcp + (grew ? ' log 在增长' : ''));
      console.log('  ' + MINUTES + ' 分钟后自动关。提前关： node mc-start.js ' + INSTANCE + ' --stop');
      process.exit(0);
    }
    lastLog = q.log;
  }

  console.log('');
  console.log('[!] ' + WAIT + ' 秒内没确认起好。**这不代表失败**，逐条查：');
  const f = probe();
  console.log('   java 进程       = ' + f.java + (f.java === 0 ? '  ← 若是 0：多半 launch-mc 没被成功调用（参数/空格），不是 OOM' : ''));
  console.log('   latest.log      = ' + f.log + 'B（看有没有在增长）');
  console.log('   session.lock    = ' + f.lock);
  console.log('   mcp 8765        = ' + f.mcp);
  process.exit(1);
}

main().catch((e) => { console.error('[X] ' + (e && e.message ? e.message : e)); process.exit(1); });