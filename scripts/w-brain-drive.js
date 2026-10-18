// w-brain-drive.js — 特殊模式 W 层驱动（playwright 直驱网页免费模型 GLM / DeepSeek）
// 角色：O（OpenCode 免费 AI）通过本脚本，用 playwright 直接进入浏览器，与 W 层两个网页模型对话。
// ★ 2026-10-05 用户裁定：池子只留 **GLM + DeepSeek**（用户原话「这两个应该最强」）。
//   Qwen / Kimi 已从默认平台集里移除 —— 不是删配置，是被 ALLOWED 白名单挡掉（见 loadPlatforms）。
//   白名单挡掉时会 console.log 一行说明，不静默丢（静默丢 = 以后没人知道为什么少一家）。
// 依据 brain-drive.js 的成熟经验：CDP 优先复用已登录浏览器，否则用持久化 profile（含登录态）。
//
// 用法：
//   node w-brain-drive.js probe                       → 枚举窗口/标签，确认两站点是否已打开/登录
//   node w-brain-drive.js open glm                    → 打开某平台页面（供人工首次登录）
//   node w-brain-drive.js ask glm "问题文本"           → 向某平台发问并取回全文
//   node w-brain-drive.js askall "问题文本"            → ★ 问题从文件读（长文本/代码块用这个）
//   node w-brain-drive.js askall --file q.txt          → 依次问 GLM + DeepSeek 两家并汇总
//   node w-brain-drive.js ask glm --file q.txt         → ★ 单家 + 文件
//   node w-brain-drive.js synth "汇总指令"             → 把最近两家回复交给 GLM 综合（O↔GLM 轮）
//   node w-brain-drive.js reset-profile               → 清 W 专用 profile 缓存
//
// 环境变量（可选）：
//   W_CDP_PORT   默认 9222；连不上则回退持久化 profile
//   W_PROFILE    默认 C:\Users\lenovo\.playwright-profile
//   W_HEADLESS=1 无头（默认有头，便于人工过验证码/登录）
//   W_TIMEOUT_MS 等待回复稳定上限，默认 600000
//   W_REPLY_DIR  回复落盘目录
//   W_PLATFORMS  平台配置 JSON 覆盖文件路径（见 w-platforms.json）

let chromium;
function resolvePlaywright() {
  const tries = [
    'playwright',
    process.env.PLAYWRIGHT_PATH,
    'D:/nodejs/global/node_modules/@playwright/mcp/node_modules/playwright',
  ].filter(Boolean);
  for (const t of tries) {
    try { return require(t); } catch (e) { /* next */ }
  }
  throw new Error('无法加载 playwright：请设置 PLAYWRIGHT_PATH 或安装 playwright');
}
chromium = resolvePlaywright().chromium;

const fs = require('fs');
const path = require('path');

const CDP_PORT = process.env.W_CDP_PORT || '9223';
const PROFILE = process.env.W_PROFILE || 'C:\\Users\\lenovo\\.playwright-profile-w';
const HEADLESS = process.env.W_HEADLESS === '1';
const TIMEOUT_MS = parseInt(process.env.W_TIMEOUT_MS || '600000', 10);
const REPLY_DIR = process.env.W_REPLY_DIR || 'E:\\restart developing doer\\rdd-selfcompile\\report\\w-replies';

// ★ 白名单：整个 W 层只允许这两家。改这里就改了池子，不用改 DEFAULT_PLATFORMS 和 w-platforms.json。
//   顺序 = askall 的问询顺序（先便宜/先出稿的一家先问，省得等两家都跑完）。
const ALLOWED = ['glm', 'deepseek'];

// 平台默认配置（选择器为兜底候选，失效时用 probe/人工核对更新 w-platforms.json）
const DEFAULT_PLATFORMS = {
  glm: {
    url: 'https://chatglm.cn/',
    open: 'https://chatglm.cn/',
    input: ['textarea', 'div[contenteditable="true"]', '[contenteditable="true"]'],
    reply: ['.markdown-body', '[class*="markdown"]', '[class*="message-content"]', 'main'],
    send: [],
    stop: ['button[aria-label*="停止"]', 'button[aria-label*="Stop"]', '[class*="stop"]'],
    continue: ['继续生成', 'Continue'],
  },
  // 2026-10-05 从 w-platforms.json 的 deepseek 段搬进来当兜底 —— 原来只在覆盖文件里有，
  // 覆盖文件一改名/删掉，deepseek 就整个消失（默认集里没有 = 池子少一家，且没人报错）。
  deepseek: {
    url: 'https://chat.deepseek.com/',
    open: 'https://chat.deepseek.com/',
    input: ['textarea', 'div[contenteditable="true"]', '[contenteditable="true"]'],
    reply: ['[class*="markdown"]', '[class*="fbb737a4"]', '.markdown-body', '[class*="response"]', 'main'],
    send: [],
    stop: ['button[aria-label*="停止"]', 'button[aria-label*="Stop"]', '[class*="stop"]'],
    continue: ['继续生成', 'Continue'],
  },
};

function loadPlatforms() {
  const p = process.env.W_PLATFORMS || path.join(__dirname, 'w-platforms.json');
  const merged = Object.assign({}, DEFAULT_PLATFORMS);
  if (fs.existsSync(p)) {
    try {
      const j = JSON.parse(fs.readFileSync(p, 'utf8'));
      for (const [k, v] of Object.entries(j)) {
        if (k.startsWith('_')) continue; // 跳过 _comment 等元键
        merged[k] = v;
      }
    } catch (e) { /* fall through */ }
  }
  // 白名单过滤放在合并之后 —— 因为 w-platforms.json 里还留着 qwen/kimi 的条目（那是历史资产，
  // 删掉会丢选择器知识）。谁把 qwen/kimi 从覆盖文件里「清理」掉了，这里也照样只有两家。
  const dropped = Object.keys(merged).filter((k) => !ALLOWED.includes(k));
  const kept = {};
  for (const k of ALLOWED) if (merged[k]) kept[k] = merged[k];
  if (dropped.length) {
    console.log(`[w] 白名单只留 ${ALLOWED.join('/')}，已忽略：${dropped.join('/')}（2026-10-05 用户裁定）`);
  }
  const missing = ALLOWED.filter((k) => !kept[k]);
  if (missing.length) {
    console.log(`[w] ★ 白名单里的 ${missing.join('/')} 没有任何选择器配置，askall 会漏掉它 —— 别当成「它没意见」`);
  }
  return kept;
}
const PLATFORMS = loadPlatforms();

function saveReply(tag, text, meta) {
  if (!fs.existsSync(REPLY_DIR)) fs.mkdirSync(REPLY_DIR, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const file = path.join(REPLY_DIR, `${tag}-${stamp}.json`);
  fs.writeFileSync(file, JSON.stringify({ tag, meta, text, chars: text.length, ts: new Date().toISOString() }, null, 2), 'utf8');
  return file;
}

async function makeContext() {
  // CDP 优先：复用用户已登录三站点的浏览器，避免 profile 锁冲突
  try {
    const browser = await Promise.race([
      chromium.connectOverCDP(`http://127.0.0.1:${CDP_PORT}`),
      new Promise((_, rej) => setTimeout(() => rej(new Error('cdp connect timeout')), 8000)),
    ]);
    const c = browser.contexts()[0];
    c.__cdp = true;
    return c;
  } catch (e) {
    return chromium.launchPersistentContext(PROFILE, {
      channel: 'msedge',
      headless: HEADLESS,
      viewport: null,
      args: [],
    });
  }
}

async function probe(ctx) {
  const out = { cdp: !!ctx.__cdp, pages: ctx.pages().length, tabs: [], platforms: {} };
  for (const p of ctx.pages()) {
    let title = '', url = '';
    try { title = (await p.title()).slice(0, 80); url = p.url(); } catch (e) {}
    out.tabs.push({ title, url });
    for (const [name, cfg] of Object.entries(PLATFORMS)) {
      try { if (url.startsWith(cfg.url)) out.platforms[name] = { url, title }; } catch (e) {}
    }
  }
  return out;
}

async function findOrOpen(ctx, url) {
  for (const p of ctx.pages()) {
    try { if (p.url().startsWith(url)) return p; } catch (e) {}
  }
  const p = await ctx.newPage();
  await p.goto(url, { timeout: 60000, waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(3000);
  return p;
}

async function findInput(page, cfg) {
  for (const sel of cfg.input) {
    const loc = page.locator(sel).filter({ hasNot: page.locator('[aria-hidden="true"]') }).first();
    try {
      if (await loc.count() > 0 && await loc.isVisible()) return loc;
    } catch (e) {}
  }
  return null;
}

// 取"最后一条回复"区域的文本长度：聚焦回复本身，避开页面动态元素(计时/光标/滚动条)
// 导致的 body.innerText 永不稳定的问题（GLM 实测：body 长 30701 持续微变 → 旧判据超时）。
async function replyLen(page, cfg) {
  try {
    return await page.evaluate((sels) => {
      for (const s of sels) {
        const els = document.querySelectorAll(s);
        if (els.length) {
          const t = (els[els.length - 1].innerText || els[els.length - 1].textContent || '').trim();
          if (t) return t.length;
        }
      }
      const main = document.querySelector('main');
      return (main ? main.innerText : document.body.innerText || '').length;
    }, cfg.reply);
  } catch (e) { return -1; }
}

async function waitReplySettled(page, cfg, timeoutMs = TIMEOUT_MS) {
  const start = Date.now();
  let prevLen = -1, stable = 0, sawNonEmpty = false;
  while (Date.now() - start < timeoutMs) {
    let len = -1;
    try { len = await replyLen(page, cfg); } catch (e) {}
    if (len > 0) sawNonEmpty = true;
    let stopCount = 0;
    for (const sel of cfg.stop) {
      try { stopCount += await page.locator(sel).count(); } catch (e) {}
    }
    // 停止钮消失 + 回复区长度连续不变 = 生成结束
    if (len === prevLen && len >= 0 && stopCount === 0) stable++; else stable = 0;
    prevLen = len;
    if (stable >= 3) { await page.waitForTimeout(800); return { settled: true }; }
    await page.waitForTimeout(1500);
  }
  // 兜底：超时但已出现非空回复 → 不 fail（GLM 等站点 body 动态噪音导致稳定判据可能永不达成）
  if (sawNonEmpty) return { settled: false, timeout: true };
  throw new Error(`等待回复稳定超时 (${timeoutMs / 1000}s)`);
}

async function clickContinueIfPresent(page, cfg) {
  return page.evaluate((patterns) => {
    for (const pat of patterns) {
      for (const b of document.querySelectorAll('button, [role="button"]')) {
        const t = (b.textContent || '').trim();
        if (t && t.includes(pat) && t.length < 20) { b.click(); return pat; }
      }
    }
    return null;
  }, cfg.continue || []);
}

async function extractReply(page, cfg) {
  return page.evaluate((sels) => {
    const PENDING = ['正在思考', '思考中', 'Thinking', '生成中', '正在生成'];
    const looksPending = (t) => PENDING.some(p => t.startsWith(p) || t === p);
    for (const s of sels) {
      const els = document.querySelectorAll(s);
      if (els.length) {
        // 从最后一条往前找"非占位(正在思考)"的助手回复
        for (let i = els.length - 1; i >= 0; i--) {
          const t = (els[i].innerText || els[i].textContent || '').trim();
          if (t && !looksPending(t)) return t;
        }
      }
    }
    const main = document.querySelector('main');
    return (main ? main.innerText : document.body.innerText) || '';
  }, cfg.reply);
}

async function askPlatform(ctx, name, question) {
  const cfg = PLATFORMS[name];
  if (!cfg) return { ok: false, why: `未知平台 ${name}` };
  const p = await findOrOpen(ctx, cfg.url);
  await p.waitForTimeout(2000);
  const box = await findInput(p, cfg);
  if (!box) return { ok: false, stage: 'input-not-found', platform: name, url: p.url(), hint: '用 probe 确认已打开并登录；必要时更新 w-platforms.json 选择器' };
  // 点击不可动时不要直接崩：Kimi 新版落地页有遮罩层，click() 会 30s 超时。
  // 回退顺序：正常点 → 强制点 → 直接 focus。只要能让它拿到焦点并接受键入即可，
  // 发送本来就走 Enter（见下方 keyboard.press('Enter')），不依赖点到发送钮。
  let clicked = false;
  try { await box.click({ timeout: 8000 }); clicked = true; }
  catch (e1) {
    try { await box.click({ timeout: 5000, force: true }); clicked = true; }
    catch (e2) {
      try { await box.focus({ timeout: 5000 }); } catch (e3) {}
    }
  }
  const isEditable = await box.getAttribute('contenteditable');
  if (isEditable) {
    await box.type(question, { delay: 5 });
  } else {
    await box.fill(question);
  }
  // 回读校验，防静默发空
  let injected = '';
  try { injected = (isEditable ? await box.innerText() : await box.inputValue()) || ''; } catch (e) {}
  if (!injected.trim()) return { ok: false, stage: 'inject-empty', platform: name };
  await p.keyboard.press('Enter');
  await p.waitForTimeout(1500);
  await waitReplySettled(p, cfg);
  for (let i = 0; i < 10; i++) {
    const c = await clickContinueIfPresent(p, cfg);
    if (!c) break;
    await p.waitForTimeout(1500);
    await waitReplySettled(p, cfg);
  }
  const reply = await extractReply(p, cfg);
  const file = saveReply(name, reply, { q: question.slice(0, 120), url: p.url() });
  if (!reply || !reply.trim()) return { ok: false, stage: 'empty-reply', platform: name, file, url: p.url() };
  // 站点级错误/人机验证拦截：识别后快速失败，便于上层标记"待人工"而不是当成有效回复。
  const errFlags = ['issue connecting', '网络错误', '请拖动', '完成验证', '验证码', 'verify you are human'];
  const hit = errFlags.find(f => reply.includes(f));
  if (hit) return { ok: false, stage: 'blocked-or-error', platform: name, flag: hit, file, url: p.url(), text: reply.slice(0, 300) };
  return { ok: true, platform: name, file, chars: reply.length, text: reply, tail: reply.slice(-1500) };
}

async function askAll(ctx, question) {
  const out = { question, results: {} };
  // 单平台硬超时：一家卡住(验证码/网络错误)不能拖垮整条 askall。
  const perPlatformMs = parseInt(process.env.W_PER_PLATFORM_MS || '240000', 10);
  for (const name of Object.keys(PLATFORMS)) {
    try {
      out.results[name] = await Promise.race([
        askPlatform(ctx, name, question),
        new Promise((_, rej) => setTimeout(() => rej(new Error(`per-platform timeout ${perPlatformMs / 1000}s`)), perPlatformMs)),
      ]);
    } catch (e) { out.results[name] = { ok: false, platform: name, err: e.message.split('\n')[0] }; }
  }
  return out;
}

async function synthViaGlm(ctx, instruction) {
  // 把最近三家回复拼进指令（调用方也可自带全文），交给 GLM 综合
  const latest = {};
  try {
    const files = fs.readdirSync(REPLY_DIR).filter(f => f.endsWith('.json'));
    for (const name of Object.keys(PLATFORMS)) {
      const mine = files.filter(f => f.startsWith(name + '-')).sort();
      if (mine.length) {
        const j = JSON.parse(fs.readFileSync(path.join(REPLY_DIR, mine[mine.length - 1]), 'utf8'));
        latest[name] = j.text || '';
      }
    }
  } catch (e) {}
  const bundle = instruction +
    '\n\n以下是三家网页模型对同一问题的回答，请综合并给出工程结论：\n' +
    Object.entries(latest).map(([k, v]) => `\n### ${k}\n${v}`).join('\n');
  return askPlatform(ctx, 'glm', bundle);
}

async function dumpDom(page) {
  return page.evaluate(() => {
    const vis = (e) => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
    const q = (s) => Array.from(document.querySelectorAll(s));
    return {
      url: location.href,
      fileInputs: q('input[type="file"]').map(e => ({ accept: e.accept, multiple: e.multiple, visible: vis(e) })),
      textareas: q('textarea').map(e => ({ ph: e.placeholder || '', visible: vis(e) })).slice(0, 12),
      editables: q('[contenteditable="true"]').map(e => ({ visible: vis(e) })).slice(0, 12),
      buttons: q('button,[role="button"]').map(e => (e.getAttribute('aria-label') || e.innerText || '').trim()).filter(t => t && t.length < 30).slice(0, 80),
    };
  });
}

async function uploadFile(page, filePath) {
  const files = Array.isArray(filePath) ? filePath : [filePath];
  // 1) filechooser via visible attach button first (Qwen/Kimi often only react to the real chooser)
  const cands = [
    '[aria-label*="上传文件和更多工具"]', 'button:has-text("上传文件和更多工具")',
    'button[aria-label*="上传"]', 'button[aria-label*="附件"]', 'button[aria-label*="文件"]',
    'button:has-text("上传")', 'button:has-text("附件")',
    '[class*="upload"]', '[class*="attach"]', '[class*="file-upload"]',
  ];
  for (const sel of cands) {
    const l = page.locator(sel).first();
    try {
      if (await l.count() === 0 || !(await l.isVisible())) continue;
      const [ch] = await Promise.all([page.waitForEvent('filechooser', { timeout: 8000 }), l.click()]);
      await ch.setFiles(files);
      return { ok: true, via: sel, files };
    } catch (e) { /* next */ }
  }
  // 2) direct hidden input (GLM works this way)
  const inputs = page.locator('input[type="file"]');
  const n = await inputs.count();
  if (n > 0) {
    let target = null;
    for (let i = 0; i < n; i++) {
      const el = inputs.nth(i);
      const accept = (await el.getAttribute('accept')) || '';
      const multiple = (await el.getAttribute('multiple')) !== null;
      if (accept === '') { target = el; break; }
      if (!target && multiple) target = el;
    }
    if (!target) target = inputs.first();
    try { await target.setInputFiles(files); return { ok: true, via: 'input[type=file]', files }; } catch (e) { /* fall through */ }
  }
  return { ok: false, why: 'no upload target found (inspect with dump)' };
}

(async () => {
  const argvRest = process.argv.slice(2);
const cmd = argvRest[0];
// ★ 2026-09-30 修「问题传不全」：原来只取 argv[3]/argv[4]，
//   问题里有空格没加引号 → 只剩前半截；含换行的代码块 → 只剩第一行。
//   现在：(a) 支持 --file <路径> 从文件读；(b) 其余参数用空格拼回，不丢。
const rest = argvRest.slice(1);
// 先把 --file <路径> 从参数里摘出来（位置随意：ask <平台> --file q.txt / askall --file q.txt）
let fileQ = null;
for (let i = 0; i < rest.length; i++) {
  if (rest[i] === '--file' || rest[i] === '-f') { fileQ = rest[i + 1]; rest.splice(i, 2); break; }
}
if (fileQ !== null && !fs.existsSync(fileQ)) {
  console.log(JSON.stringify({ ok: false, why: 'file not found: ' + fileQ }));
  process.exit(1);
}
const fileBody = fileQ !== null ? fs.readFileSync(fileQ, 'utf8').trim() : null;

let a1, a2;
if (cmd === 'ask') {
  a1 = rest[0];                                   // 平台
  a2 = fileBody !== null ? fileBody : rest.slice(1).join(' ');   // 问题
} else {
  a1 = fileBody !== null ? fileBody : rest.join(' ');            // askall 的问题
  a2 = '';
}
  const ctx = await makeContext();
  let out;
  if (cmd === 'probe') out = await probe(ctx);
  else if (cmd === 'open') {
    const cfg = PLATFORMS[a1];
    if (!cfg) { out = { ok: false, why: `未知平台 ${a1}` }; }
    else { const p = await findOrOpen(ctx, cfg.open); out = { ok: true, platform: a1, url: p.url() }; }
  } else if (cmd === 'ask') {
    out = a2 ? await askPlatform(ctx, a1, a2) : { ok: false, why: 'need: ask <platform> <question>' };
  } else if (cmd === 'askall') {
    out = a1 ? await askAll(ctx, a1) : { ok: false, why: 'need: askall <question>' };
  } else if (cmd === 'synth') {
    out = a1 ? await synthViaGlm(ctx, a1) : { ok: false, why: 'need: synth <instruction>' };
  } else if (cmd === 'dump') {
    const cfg = PLATFORMS[a1];
    out = cfg ? await dumpDom(await findOrOpen(ctx, cfg.url)) : { ok: false, why: `未知平台 ${a1}` };
  } else if (cmd === 'upload') {
    const cfg = PLATFORMS[a1];
    if (!cfg) { out = { ok: false, why: `未知平台 ${a1}` }; }
    else if (!a2 || !fs.existsSync(a2)) { out = { ok: false, why: `文件不存在: ${a2}` }; }
    else { const p = await findOrOpen(ctx, cfg.url); out = await uploadFile(p, a2); }
  } else if (cmd === 'shot') {
    const cfg = PLATFORMS[a1];
    if (!cfg) { out = { ok: false, why: `未知平台 ${a1}` }; }
    else {
      const p = await findOrOpen(ctx, cfg.url);
      await p.waitForTimeout(3000);
      const file = a2 || path.join(REPLY_DIR, `shot-${a1}-${Date.now()}.png`);
      const dir = path.dirname(file);
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
      await p.screenshot({ path: file });
      out = { ok: true, platform: a1, url: p.url(), file };
    }
  } else if (cmd === 'reset-profile') {
    if (fs.existsSync(PROFILE)) fs.rmSync(PROFILE, { recursive: true, force: true });
    out = { ok: true, action: 'profile reset' };
  } else {
    out = { ok: false, why: `unknown cmd ${cmd}`, usage: 'probe|open <p>|ask <p> <q>|askall <q>|synth <i>|reset-profile' };
  }
  console.log(JSON.stringify(out, null, 2));
  if (ctx.__cdp) process.exit(0);
  await ctx.close();
})().catch(e => { console.error(JSON.stringify({ ok: false, err: e.message.split('\n')[0] })); process.exit(1); });
