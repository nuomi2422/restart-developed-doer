const { chromium } = require('playwright');

// 外脑驱动脚本：固定两个专属浏览器窗口（DeepSeek 专家模式 / ChatGPT），保持登录态
// 用法：
//   node brain-drive.js probe                  → 枚举窗口/标签/登录态
//   node brain-drive.js ds "问题文本"           → DeepSeek 发消息并读回复
//   node brain-drive.js gpt "问题文本"          → ChatGPT 发消息并读回复
//   node brain-drive.js reset-profile          → 清外脑专用 profile 缓存（cookies 保留）

const PROFILE = "C:\\Users\\lenovo\\.playwright-profile";
// 固定会话勿换：DeepSeek 审核对话（全源码上下文在此）。绝不要 goto 首页（会新开空白对话丢上下文）。
const DS_URL = "https://chat.deepseek.com/a/chat/s/f0791612-ae66-4a67-8aeb-b60c1f692bbc";
const GPT_URL = "https://chatgpt.com/";
const REPLY_DIR = "C:\\Users\\lenovo\\AppData\\Local\\Temp\\opencode\\brain-replies";

function saveReply(tag, text, meta) {
  const fs = require('fs');
  if (!fs.existsSync(REPLY_DIR)) fs.mkdirSync(REPLY_DIR, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const file = `${REPLY_DIR}\\${tag}-${stamp}.json`;
  fs.writeFileSync(file, JSON.stringify({ tag, meta, text, ts: new Date().toISOString() }, null, 2), 'utf8');
  return file;
}

async function makeContext() {
  // CDP 优先：若 9222（edge-numen-win 已登录 DS/含固定会话）在跑，直接复用现有浏览器；
  // 不要在用户已打开的窗口上再起 persistent（profile 锁冲突），也不要关用户的浏览器。
  try {
    const browser = await Promise.race([
      chromium.connectOverCDP("http://127.0.0.1:9222"),
      new Promise((_, rej) => setTimeout(() => rej(new Error("cdp connect timeout")), 8000))
    ]);
    const c = browser.contexts()[0];
    c.__cdp = true;
    return c;
  } catch (e) {
    return chromium.launchPersistentContext(PROFILE, {
      channel: 'msedge',
      headless: false,
      viewport: null,
      args: []
    });
  }
}

async function probe(ctx) {
  const pages = ctx.pages();
  const out = { pages: pages.length, tabs: [] };
  for (const p of pages) {
    let title = '', url = '';
    try { title = (await p.title()).slice(0, 80); url = p.url(); } catch (e) {}
    out.tabs.push({ title, url });
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

async function waitReplySettled(page, timeoutMs = 600000) {
  // 等生成真正结束：body 文本连续 ~6s 不变 且 停止/暂停钮消失，而不是固定等十几秒抓中途快照
  const start = Date.now();
  let prevLen = -1, stable = 0;
  while (Date.now() - start < timeoutMs) {
    let len = -1;
    try { len = (await page.locator('body').innerText()).length; } catch (e) {}
    const stopCount = await page.locator('button[aria-label*="Stop"], button[aria-label*="停止"], button[aria-label*="Pause"], button[aria-label*="Interrupt"]').count();
    if (len === prevLen && stopCount === 0) { stable++; } else { stable = 0; }
    prevLen = len;
    if (stable >= 3) { await page.waitForTimeout(1000); return; }
    await page.waitForTimeout(2000);
  }
  throw new Error(`等待回复稳定超时 (${timeoutMs / 1000}s)`);
}

async function clickContinueIfPresent(page, pattern) {
  // ChatGPT 输出 token 上限会把长回复断开成「继续生成」：自动点在最后一条 assistant 消息里
  return page.evaluate((reSrc) => {
    const re = new RegExp(reSrc, 'i');
    const assistants = document.querySelectorAll('[data-message-author-role="assistant"]');
    const root = assistants.length ? assistants[assistants.length - 1] : document.body;
    for (const b of root.querySelectorAll('button')) {
      if (re.test((b.textContent || '').trim())) { b.click(); return true; }
    }
    return false;
  }, pattern);
}

async function waitReplyComplete(page, platform) {
  await waitReplySettled(page);
  if (platform === 'gpt') {
    for (let i = 0; i < 20; i++) {
      const clicked = await clickContinueIfPresent(page, '继续生成|Continue generating');
      if (!clicked) break;
      console.log(`[continue] 自动点击继续生成 #${i + 1}`);
      await page.waitForTimeout(1500);
      await waitReplySettled(page);
    }
  }
}

async function extractReply(page) {
  // 取最后一条回复容器全文；无角色容器则退化到 .ds-markdown/.markdown/main，再无则整页。
  // 不再做 slice(-6000) 字符截断——那正是"取到一定程度就取不了"的根因之一。
  return page.evaluate(() => {
    const pick = (s) => {
      const els = document.querySelectorAll(s);
      if (!els.length) return '';
      const last = els[els.length - 1];
      return (last.innerText || last.textContent || '').trim();
    };
    let t = pick('[data-message-author-role="assistant"]');
    if (!t) t = pick('.ds-markdown');
    if (!t) t = pick('.markdown');
    if (!t) t = pick('main');
    return t || document.body.innerText || '';
  });
}

async function dsAsk(ctx, question) {
  const p = await findOrOpen(ctx, DS_URL);
  await p.waitForTimeout(2000);
  const box = p.locator('textarea[placeholder="给 DeepSeek 发送消息"], textarea[placeholder*="发送消息"], div[contenteditable="true"]').first();
  try { await box.waitFor({ state: 'visible', timeout: 45000 }); } catch (e) {
    return { ok: false, stage: 'input-not-found', url: p.url(), tries: await probe(ctx) };
  }
  await box.click();
  if (await box.getAttribute('contenteditable')) await box.type(question, { delay: 5 });
  else await box.fill(question);
  await p.keyboard.press('Enter');
  await waitReplyComplete(p, 'ds');
  const reply = await extractReply(p);
  const f = saveReply('ds', reply, { q: question.slice(0, 80) });
  return { ok: true, file: f, chars: reply.length, tail: reply.slice(-2000) };
}

async function gptAsk(ctx, question) {
  // 免登录链路：先清 openai 域 cookie，避免残留 OAuth 跳 Google 登录（仅非 CDP 模式；
  // 复用 9222 的共享浏览器时清 cookie 会连带清掉 DeepSeek 登录态）
  const p = await findOrOpen(ctx, GPT_URL);
  if (!ctx.__cdp) {
    await p.context().clearCookies({ domain: 'chatgpt.com' });
    await p.context().clearCookies({ domain: 'openai.com' });
    await p.context().clearCookies({ domain: 'auth0.openai.com' });
  }
  await p.goto(GPT_URL, { timeout: 60000, waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(4000);
  const box = p.locator('#mobile-composer-prompt, #prompt-textarea, textarea[placeholder*="聊天"], textarea[placeholder*="message"], div[contenteditable="true"], div.ProseMirror').first();
  try { await box.waitFor({ state: 'visible', timeout: 45000 }); } catch (e) {
    return { ok: false, stage: 'input-not-found', url: p.url() };
  }
  await box.click();
  if (await box.getAttribute('contenteditable')) await box.type(question, { delay: 5 });
  else await box.fill(question);
  await p.keyboard.press('Enter');
  await waitReplyComplete(p, 'gpt');
  const reply = await extractReply(p);
  const f = saveReply('gpt', reply, { q: question.slice(0, 80) });
  return { ok: true, file: f, chars: reply.length, tail: reply.slice(-2000) };
}

(async () => {
  const [,, cmd, arg] = process.argv;
  const ctx = await makeContext();
  if (cmd === 'probe') {
    console.log(JSON.stringify(await probe(ctx), null, 2));
  } else if (cmd === 'ds') {
    if (!arg) { console.log(JSON.stringify({ ok: false, why: 'need question' })); process.exit(1); }
    console.log(JSON.stringify(await dsAsk(ctx, arg), null, 2));
  } else if (cmd === 'gpt') {
    if (!arg) { console.log(JSON.stringify({ ok: false, why: 'need question' })); process.exit(1); }
    console.log(JSON.stringify(await gptAsk(ctx, arg), null, 2));
  } else if (cmd === 'reset-profile') {
    const fs = require('fs');
    if (fs.existsSync(PROFILE)) fs.rmSync(PROFILE, { recursive: true, force: true });
    console.log(JSON.stringify({ ok: true, action: 'profile reset' }));
  } else {
    console.log(JSON.stringify({ ok: false, why: 'unknown cmd ' + cmd }));
  }
  // CDP 模式是用户的常驻浏览器（edge-numen-win，不能关）；但 CDP socket 会拖住 node 不退出，
  // 必须显式 process.exit。独立 persistent 则测完关。
  if (ctx.__cdp) process.exit(0);
  await ctx.close();
})().catch(e => { console.error(JSON.stringify({ ok: false, err: e.message.split('\n')[0] })); process.exit(1); });