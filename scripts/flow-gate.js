// flow-gate.js -- 自编译闭环门禁：**没走完就不许收工**。
//
// 为什么要有这个（2026-09-28 真实教训）：`10-总流程与双模式.md` 一直写着"走工程规范"，
// 06-检查清单 也有勾选项，但它们都是**文字**，AI 可以"读过了"就当走过。于是出现过：
// 改了 core 层执行代码 → 手写 gradle 编译 → 手动部署 → 跑完单测就收工，
// 整段 new-mutation / gen-code / build-jar / deploy / verify / store 证据链全跳，
// 外脑审也没做，而报告读起来完全像走完了流程。
//
// 所以门禁必须**查证据**，而不是查"读没读过文档"。本脚本只认盘上的实物：
// 有没有 mutation 工作区的 phase-*.json、部署的 jar 与构建产物 sha 是否一致、
// git 有没有本轮 commit/tag、测试日志有没有今日条目、外脑审有没有痕迹、
// store 有没有被（不该被）promote。
//
// 用法: node flow-gate.js [--since <YYYY-MM-DD>] [--tag <gitTag>]
// 退出: 0=全过（可收工）；1=有 BLOCKER（不许收工）；2=用法/环境错
const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const SCRIPTS = __dirname;
const MUTATIONS = path.join(SCRIPTS, '..', 'mutations');
const NUMEN = 'D:\\rdd\\workspace\\minecraft-numen';
const LOG = 'D:\\rdd\\docs\\rdd-docs\\rdd架构\\测试日志.md';
const UPDLOG = 'D:\\rdd\\docs\\rdd-docs\\rdd架构\\自编译统一版\\09-更新日志.md';
const MC_GAME_DIR = 'D:\\rdd\\mc\\The Best of Twilight Forest';
const MODS = path.join(MC_GAME_DIR, 'mods');

const argv = process.argv.slice(2);
const sinceIdx = argv.indexOf('--since');
const SINCE = sinceIdx >= 0 && argv[sinceIdx + 1] ? argv[sinceIdx + 1]
  : new Date(Date.now() - 36 * 3600 * 1000).toISOString().slice(0, 10);
const tagIdx = argv.indexOf('--tag');
const TAG = tagIdx >= 0 && argv[tagIdx + 1] ? argv[tagIdx + 1] : 'pre-autofix-20260928';

const rows = [];
// ★ 2026-09-30：门禁性质标签（🔒查实物/📝查痕迹）默认【不输出】——
//   AI 读得到门禁输出，标签等于给它一张「哪些检查能糊弄」的地图。
//   要看标签请加 --human（那是给人看的）。判定级别不受影响。
const HUMAN_FLAG = process.argv.includes('--human');
function chk(sev, name, ok, detail, kind) { rows.push({ sev, name, ok, detail: detail || '', kind: kind || guessKind(name) }); }
// 性质判定：查实物 / 查痕迹 / 人工
function guessKind(name) {
  const n = String(name);
  if (/sha|哈希|jar|phase-|回滚点|tag|commit|VERIFIED|契约回归|测试.*0 失败|行为证据|部署/.test(n)) return '查实物';
  if (/清单|思考|自问|留痕|落盘|写了|登记|列出|更新了|对照过|查过/.test(n)) return '查痕迹';
  return '查痕迹';
}
function sh(cmd, cwd) {
  try { return cp.execSync(cmd, { cwd, encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'] }).trim(); }
  catch (e) { return ''; }
}
function sha(p) {
  try { return cp.execSync('certutil -hashfile "' + p + '" SHA256', { encoding: 'utf8', timeout: 60000 })
    .split(/\r?\n/).filter(x => /^[0-9a-fA-F]{64}/.test(x.trim()))[0].trim().toUpperCase(); }
  catch (e) { return ''; }
}
// phase 明细太长会淹掉输出，只留前若干个 + 总数
function coreDetailBrief(list) {
  const head = list.slice(0, 4).join(' | ');
  return (list.length > 4 ? head + ' … 共' + list.length + '个' : head);
}

// ---- ① 自编译闭环：mutation 工作区是否真的建了、跑到哪一步 ----
// 只认 phase-*.json 实物。只有 phase-launch.json 不足以说明"编译→部署→验证"跑过，
// 所以要求至少见到 build / deploy / verify 之一的证据文件。
let phaseDirs = [], phaseDetail = [];
try {
  const cutoff = new Date(SINCE + 'T00:00:00').getTime();
  for (const d of fs.readdirSync(MUTATIONS)) {
    const dir = path.join(MUTATIONS, d);
    let s; try { s = fs.statSync(dir); } catch (e) { continue; }
    if (!s.isDirectory()) continue;
    const phases = fs.readdirSync(dir).filter(f => /^phase-.*\.json$/.test(f));
    if (!phases.length) continue;
    let newest = 0;
    for (const f of phases) { try { newest = Math.max(newest, fs.statSync(path.join(dir, f)).mtimeMs); } catch (e) {} }
    if (newest < cutoff) continue;
    const steps = phases.map(f => f.replace(/^phase-|\.json$/g, '')).join(',');
    phaseDirs.push(d);
    phaseDetail.push({ dir: d, steps: steps, list: steps.split(',') });
  }
} catch (e) { /* mutations 目录不存在也是一种事实 */ }
const coreSteps = phaseDetail.filter(x => x.list.some(s => /^(build|deploy|verify)$/.test(s)));
chk(coreSteps.length ? 'ok' : 'block',
  '① 走过自编译闭环（mutation 工作区有 build/deploy/verify 阶段的 phase-*.json）',
  coreSteps.length > 0,
  coreSteps.length ? coreDetailBrief(coreSteps.map(x => x.dir + '[' + x.steps + ']'))
    : ('本轮 mutation 工作区=' + phaseDirs.length + ' 个，但没有一个含 build/deploy/verify 阶段 —— 整段闭环被绕过'), '查实物');

// ---- ② 部署的 jar 与构建产物 sha 是否一致（部署错 jar = 验证了个寂寞） ----
const deployedCore = path.join(MODS, 'numen-neoforge-1.21.1-0.1.3-dev.jar');
const builtCore = path.join(NUMEN, 'core', 'neoforge', 'build', 'libs', 'numen-neoforge-1.21.1-0.1.3-dev.jar');
let jarOk = false, jarDetail = '';
if (fs.existsSync(deployedCore) && fs.existsSync(builtCore)) {
  const a = sha(deployedCore), b = sha(builtCore);
  jarOk = !!a && a === b;
  jarDetail = 'deployed=' + (a ? a.slice(0, 12) : '?') + ' built=' + (b ? b.slice(0, 12) : '?');
} else {
  jarDetail = '缺 deployed 或 built jar';
}
chk(jarOk ? 'ok' : 'block', '② 部署的 jar == 构建产物（sha 一致，证明没部署错模块）', jarOk, jarDetail, '查实物');

// ---- ③ 回滚点：git tag + 本轮 commit + 部署前 jar 备份 ----
// 两个实测踩坑，都绕开：
//   a) `git log --since=` 本机返回空（提交日期明明在范围内）→ 改成把最近提交的 Date 读回来在 JS 里比。
//   b) `--format=%h|%cI` 里的 `|` 被 cmd.exe 当成管道、整条命令失败 → 不用 % 格式、不用 |，
//      直接读 `git log -20` 的默认输出并解析 `Date:` 行。
const tagHit = sh('git tag --list "' + TAG + '"', NUMEN);
const rawLog = sh('git log -20', NUMEN);
const dateRe = /^Date:\s+(.+)$/gm;
const dateStrs = [];
let m;
while ((m = dateRe.exec(rawLog)) !== null) dateStrs.push(m[1].trim());
const cutoffMs = new Date(SINCE + 'T00:00:00').getTime();
const commitsToday = dateStrs.filter(d => { const t = new Date(d).getTime(); return isFinite(t) && t >= cutoffMs; }).length;
const headSha = (rawLog.split(/\r?\n/)[0] || '').replace(/^commit\s+/, '').slice(0, 9);
const baks = fs.existsSync(MODS) ? fs.readdirSync(MODS).filter(f => /\.pre-autofix-.*\.bak$/.test(f)) : [];
chk((tagHit && commitsToday > 0 && baks.length) ? 'ok' : 'block',
  '③ 回滚点齐（tag + 本轮 commit + 部署前 jar 备份）',
  !!(tagHit && commitsToday > 0 && baks.length),
  'tag=' + (tagHit || '无') + ' 本轮commits=' + commitsToday + '/读到的提交日期' + dateStrs.length
    + ' jarBaks=' + baks.length + ' head=' + (headSha || '?'), '查实物');

// ---- ④ 记录：测试日志今日条目 + 运维文档 ----
let logOk = false, logDetail = '读不到测试日志';
if (fs.existsSync(LOG)) {
  const t = fs.readFileSync(LOG, 'utf8');
  logOk = t.includes(SINCE);
  logDetail = logOk ? '测试日志含 ' + SINCE : '测试日志无 ' + SINCE + ' 条目';
}
chk(logOk ? 'ok' : 'block', '④ 写了测试日志（档位/目标/改动/测试/证据/未完成/下一步）', logOk, logDetail, '查痕迹');
let docOk = false, docDetail = '';
try {
  const docs = fs.readdirSync('D:\\rdd\\docs\\rdd-docs\\rdd架构\\自编译统一版').filter(f => f.endsWith('.md') && /维护说明|自修复/.test(f));
  docOk = docs.length > 0; docDetail = docs.join(', ');
} catch (e) { docDetail = '读不到统一版目录'; }
chk(docOk ? 'warn' : 'block', '⑤ 写了解释性维护说明（改了哪/为什么/怎么调/入口出口）', docOk, docDetail, '查痕迹');

// ---- ⑥ 外脑审（省额度不是跳过审的理由：网页外脑零成本，codex 按需） ----
// 判据不能是"文档里出现过'外脑'三个字"——那只会把"引用了别人的方案"误判成"我审过了"。
// 只认**本轮落盘的审稿实物**：审稿结果文件必须在 since 之后被写过。
const REVIEW_DIRS = [
  path.join(process.env.TEMP || 'C:\\Users\\admin\\AppData\\Local\\Temp', 'opencode', 'brain-replies'),
  path.join(SCRIPTS, '..', 'report'),
  path.join(SCRIPTS, '..', 'review'),
];
let reviewHits = [], extOk = false, extDetail = '';
try {
  const cut = new Date(SINCE + 'T00:00:00').getTime();
  for (const d of REVIEW_DIRS) {
    if (!fs.existsSync(d)) continue;
    const walk = (dir, depth) => {
      if (depth > 3) return;
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) { walk(p, depth + 1); continue; }
        if (!/\.(json|txt|md|log)$/i.test(e.name)) continue;
        if (!/review|审|brain|codex|reply/i.test(e.name + p)) continue;
        let mt = 0; try { mt = fs.statSync(p).mtimeMs; } catch (x) { continue; }
        if (mt >= cut) { reviewHits.push(path.basename(p)); extOk = true; }
      }
    };
    walk(d, 0);
  }
} catch (e) { /* 目录不存在也是一种事实 */ }
if (!extOk) {
  extDetail = '本轮没有落盘的审稿实物（查过：' + REVIEW_DIRS.map(d => path.basename(d)).join('/')
    + '）。文档里"提到外脑"不算审 —— 必须把审稿结果存成文件。'
    + '省额度不是跳过审的理由：codex 走 gpt-6-astra，网页外脑零成本。';
} else {
  extDetail = '本轮审稿实物：' + reviewHits.slice(0, 3).join(', ') + (reviewHits.length > 3 ? ' …共' + reviewHits.length : '');
}
chk(extOk ? 'ok' : 'block', '⑥ 外脑审至少一家，且审稿结果已落盘', extOk, extDetail, '查痕迹');

// ---- ⑦ store：未拿到行为证据前不许 promote ----
// 判据必须能区分"我 promote 的"和"开工前就 active 的"。用日期去猜 active 版本名是错的
// （day2b-20260928 是**上一轮**就 promote 的，会被误判成今天 promote）。所以必须由调用方
// 传开工时的 active 作基线；没传就只能报，不能拦。
const activeIdx = argv.indexOf('--active');
const ACTIVE_AT_START = activeIdx >= 0 && argv[activeIdx + 1] ? argv[activeIdx + 1] : '';
let storeLine = '';
try { storeLine = sh('node "' + path.join(SCRIPTS, 'selfcompile-store.js') + '" status'); } catch (e) {}
const active = (storeLine.match(/version=(\S+)/) || [])[1] || '?';
if (ACTIVE_AT_START) {
  const moved = active !== ACTIVE_AT_START;
  chk(!moved ? 'ok' : 'block', '⑦ 未在无行为证据时 promote', !moved,
    'active: 开工时=' + ACTIVE_AT_START + ' 现在=' + active + (moved ? ' —— active 被动了' : ' （未动）'), '查实物');
} else {
  chk(true, '⑦ promote 检查（未给基线，只能报不能拦）', true,
    '当前 active=' + active + '；传 --active <开工时的版本> 才能真正拦"擅自 promote"', '查实物');
}

// ---- ⑧ 行为未验证的必须写清触发条件与验证场景 ----
let behOk = false, behDetail = '未找到「未完成/未验证 + 验证场景」段落';
try {
  const t = fs.readFileSync(LOG, 'utf8');
  const tail = t.slice(-9000);
  behOk = /未(完成|证)/.test(tail) && /(验证场景|触发条件|怎么验|未证)/.test(tail);
  if (behOk) behDetail = '已列出未验证项与验证场景';
} catch (e) {}
chk(behOk ? 'ok' : 'warn', '⑧ 行为未验证项已逐条列出（触发条件+验证场景）', behOk, behDetail, '查痕迹');

// ---- 报 ----
const blockers = rows.filter(r => r.sev === 'block');
const warns = rows.filter(r => r.sev === 'warn');
console.log('===== 自编译闭环门禁 flow-gate =====');
console.log('基准: since=' + SINCE + '  tag=' + TAG);
for (const r of rows) {
  const KI = { '查实物': '🔒', '查痕迹': '📝', '人工': '👤' };
  console.log((r.ok ? '[PASS] ' : (r.sev === 'warn' ? '[WARN] ' : '[BLOCK] ')) + (HUMAN_FLAG ? (KI[r.kind] + ' ') : '') + r.name + (r.detail ? '  -- ' + r.detail : ''));
}
console.log('--------------------------------------');
const gateReal = rows.filter(r => r.kind === '查实物'), gateTrace = rows.filter(r => r.kind === '查痕迹');
console.log('PASS=' + rows.filter(r => r.ok).length + ' WARN=' + warns.length + ' BLOCK=' + blockers.length);
if (HUMAN_FLAG) {
  console.log('性质：🔒 查实物 ' + gateReal.length + ' 项（AI 编不出来） ｜ 📝 查痕迹 ' + gateTrace.length + ' 项（写得出来就过 → 观察对象）');
  console.log('  🔒 查实物: ' + gateReal.map(r => r.name.replace(/^[①-⑨\s]+/, '').slice(0, 24)).join(' / '));
  console.log('  📝 查痕迹: ' + gateTrace.map(r => r.name.replace(/^[①-⑨\s]+/, '').slice(0, 24)).join(' / '));
  console.log('  ★ 观察点：查痕迹那几项若每轮都秒过 = 只在走过场，可降级；反复卡住 = 真在拦，别削。');
}
console.log(blockers.length
  ? 'VERDICT=NOT_DONE —— 有 BLOCKER，**不许收工**，不许对外说「走完流程了」'
  : 'VERDICT=FLOW_COMPLETE —— 闭环各步都有盘上证据');
process.exit(blockers.length ? 1 : 0);
