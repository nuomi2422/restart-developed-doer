import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

// Reuse an installed Playwright and Chrome, without adding runtime UI dependencies.
const modulePath = process.env.PLAYWRIGHT_MODULE;
if (!modulePath) throw new Error('Set PLAYWRIGHT_MODULE to an installed playwright/index.mjs');
const { chromium } = await import(pathToFileURL(modulePath));
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const MONITOR_URL = process.env.MONITOR_URL || 'http://127.0.0.1:8776/';
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(MONITOR_URL);

  // 2026-10-03：15 个平铺页签改成两级（[data-zone] 区块 + [data-sub] 子块）。
  // 子块按钮只渲染当前区块的，所以切子块前必须先切区块 —— 这一步收在 helper 里，
  // 免得每条用例都写成两行而漏掉。
  async function openSub(zone, sub) {
    await page.locator(`[data-zone="${zone}"]`).click();
    await page.locator(`[data-sub="${sub}"]`).click();
    await page.waitForFunction(id => {
      const b = document.querySelector(`[data-sub="${id}"]`);
      return !!b && b.classList.contains('active');
    }, sub);
  }
  const contentText = () => page.locator('#monitor-content').innerText();
  const hostText = id => page.locator(`#${id}`).innerText();
  // 这四个 host 是各自 fetch 自己的接口（/api/chain、/api/experience-chain、
  // /api/experience-library、/api/acx），首帧只能看到「正在读…」。
  // 而且日志流首批快照会把主线程占住一到两秒 —— 所以必须**等**，不能断言完就下一条。
  const waitHost = (id, needle) => page.waitForFunction(([hostId, want]) => {
    const el = document.getElementById(hostId);
    return !!el && !el.hidden && el.innerText.includes(want);
  }, [id, needle], { timeout: 20000 }).then(() => hostText(id));
  // 内联卡片（#page-body）同理：日志流首批快照可能还没到，页面此刻是诚实空态。
  // 直接断言会偶发红 —— 那不是回归，是读得太早。
  const expectText = async needle => {
    try {
      await page.waitForFunction(w => document.getElementById('monitor-content').innerText.includes(w), needle, { timeout: 20000 });
    } catch (e) {
      throw new Error(`#monitor-content 里始终没有「${needle}」`);
    }
  };
// ★ 2026-10-03 修的那个 bug 的直接判据：那四个页签过去渲染出来的是字符串
//   "undefined"（view 画完被 render() 末尾无条件 content.innerHTML 抹掉）。
//   只查 #page-body —— 那是「本子块的卡片」容器，宿主区里的内容是数据，
//   里面出现 undefined 是**合法**的：真有一个 AC 脚本叫 `b9_vars_undefined`。
  const assertNoUndefined = async where => {
    const t = await page.locator('#page-body').innerText();
    assert.ok(!t.includes('undefined'), `${where} 渲染出了字面量 undefined`);
  };

  // ── 默认落在 ① Agent Context 的第一块 ───────────────────────────────────
  await page.locator('#rdd-companion').waitFor();
  assert.equal(await page.locator('#page-title').innerText(), '1.1 Supervisor 输入/输出');
  await expectText('任务链 AI · Supervisor');
  assert.ok(!(await contentText()).includes('执行 AI · Numen'));
  assert.ok(!(await contentText()).includes('MOCK'));
  // 6 个区块（五个分析区块 + 脚手架区），当前区块 5 个子块。
  assert.equal(await page.locator('#zone-tabs button').count(), 6);
  assert.equal(await page.locator('#sub-tabs button').count(), 6); // agent 区现在是 1.1~1.6（2026-10-04 加了 1.6 Token）
  assert.ok((await page.locator('#sub-gloss').innerText()).length > 10, '子块一句话说明要显示出来');

  // ── ③ 任务链：本体 / 资产 / 支线 / 断点 / 监督 ──────────────────────────
  await openSub('taskchain', 'chainbody');
  assert.equal(await page.locator('#page-title').innerText(), '3.1 任务链本体');
  await expectText('资产库');
  assert.equal(await page.locator('.task-flow').count(), 1);
  // Toggle state must survive asynchronous journal refreshes.
  // 2026-10-03：原来这里点的是 `all-primaries-*` / `primary-*`，但**改造前就不存在**
  // —— taskchain 页走 RddView.renderTaskChain()，那两个 disclosure 只在 RddView.render()
  // （旧的 `rdd` 页）里产出。所以这条断言从来没绿过，属于陈旧选择器，不是回归。
  // 换成 3.1 上真实存在的折叠：历史与排错（history-*）+ 它内层的上下文条（context-*）。
  const history = page.locator('details[data-detail-id^="history-"]').first();
  assert.equal(await history.getAttribute('open'), null, '历史与排错默认应收起');
  await history.locator(':scope > summary').click();
  // 内层 contextRows 的第一条是**默认展开**的（rdd-view.js disclosure 的 initial=count===0），
  // 所以这里验证的是「点开后收起，刷新后仍保持收起」——收起态同样要能扛住异步重建。
  const ctxDetail = page.locator('details[data-detail-id^="context-"]').first();
  assert.equal(await ctxDetail.getAttribute('open'), '', '上下文条默认展开');
  await ctxDetail.locator(':scope > summary').click();
  assert.equal(await ctxDetail.getAttribute('open'), null);
  await page.evaluate(() => MonitorStore.accept({ type: 'connection', payload: MonitorStore.health() }));
  await page.waitForTimeout(1200);
  assert.equal(await history.getAttribute('open'), '', '异步刷新后外层折叠必须仍是展开的');
  assert.equal(await ctxDetail.getAttribute('open'), null, '异步刷新后内层折叠必须仍是收起的');

  await openSub('taskchain', 'assets');
  await expectText('资产库 · 本次观测');
  await openSub('taskchain', 'branches');
  await expectText('支线总览');
  // ── 3.4 断点与缺口 / 5.1 经验库 / 5.2 数据流：同样要等自己的接口 ──────────
  await openSub('taskchain', 'gaps');
  assert.ok((await waitHost('chain-host', '经验链')).length > 0, '3.4 断点与缺口必须有内容');
  await assertNoUndefined('3.4 断点与缺口');
  await openSub('taskchain', 'supervision');
  await expectText('监督开关 · 空转止血');

  // ── ② 工具：能力表 / 工具面 / 埋点 ────────────────────────────────────
  await openSub('tools', 'rddtools');
  await expectText('工具面');
  await openSub('tools', 'instrumentation');
  await expectText('埋点');

  // ── ① Agent Context：Numen / 历史上下文 / 历史对话 / 学习者 ───────────
  await openSub('agent', 'numen');
  assert.equal(await page.locator('#page-title').innerText(), '1.2 Numen 输入/输出');
  await expectText('Numen AI · 执行上下文');
  await page.locator('#font-scale').selectOption('xlarge');
  assert.equal(await page.locator('html').getAttribute('data-font-scale'), 'xlarge');
  await openSub('agent', 'context');
  await expectText('Numen 已记录');
  await openSub('agent', 'ai');
  await expectText('Numen · 历史对话与日志摘要');
  await openSub('agent', 'learner');
  await expectText('学习者');

  // ── ④ ACX：内容 / 生命周期日志 / 运行（三个子块共用 #acx-host）─────────
  await openSub('acx', 'acx-content');
  assert.ok((await waitHost('acx-host', 'ACX · 内容')).includes('ACX · 内容'));
  await assertNoUndefined('4.1 ACX 内容');
  await openSub('acx', 'acx-lifecycle');
  assert.ok((await waitHost('acx-host', 'ACX · 生命周期日志')).includes('ACX · 生命周期日志'));
  await openSub('acx', 'acx-runs');
  assert.ok((await waitHost('acx-host', 'ACX · 运行')).includes('ACX · 运行'));

  // ── ⑤ 经验携带器：经验库 / 数据流 / 队列 / 携带器判定 ─────────────────
  await openSub('carrier', 'explib');
  assert.ok((await waitHost('explib-host', '经验库 · 按内容')).includes('经验库 · 按内容'));
  await assertNoUndefined('5.1 经验库内容');
  await openSub('carrier', 'expchain');
  assert.ok((await waitHost('experience-chain', '经验链')).length > 0, '5.2 经验链数据流必须有内容');
  await openSub('carrier', 'queue');
  await expectText('统一队列 MemoQueue');
  await openSub('carrier', 'carrier-assess');
  await expectText('携带器');

  // ── 脚手架区：介绍 / 诊断 / 工具目录 ───────────────────────────────────
  await openSub('ops', 'intro');
  await page.getByText('项目介绍 · RDD 八模块 / 定位 / 验收边界', { exact: true }).waitFor();
  await page.getByText('8. **Wiki 查询**', { exact: false }).waitFor();
  await openSub('ops', 'health');
  await page.getByText('最后观测', { exact: true }).waitFor();
  assert.ok(!(await contentText()).includes('HEALTHY'));
  await openSub('ops', 'docs');
  await expectText('项目文档');
  await openSub('tools', 'tools');
  await page.getByText('Numen 工具目录 · 只读查询', { exact: true }).waitFor();
  await page.waitForFunction(() => /工具目录未取得|MCP 当前暴露/.test(document.getElementById('monitor-content').innerText));

  // ── 1.6 Token 消耗速率 ───────────────────────────────────────────────
  // 判据分两类：① 「读不到 ≠ 没烧」这句话必须出现在页面上（读不到时要显式说）
  //            ② 速率/台账/水位三块都在，且三块不许被说成能相加
  await openSub('agent', 'tokens');
  assert.ok(await waitHost('token-host', 'Token 消耗'), '1.6 的宿主没出内容');
  await expectText('消耗速率 · token / 分钟');
  await expectText('会话台账 · 累计');
  await expectText('上下文水位');
  await expectText('不能和上面的逐轮合计相加');
  await expectText('推算');
  const tokTxt = await hostText('token-host');
  assert.ok(tokTxt.includes('token / 分钟'), '速率卡要有单位');
  assert.ok(!tokTxt.includes('undefined'), '1.6 不能出现 undefined');
  const barCount = await page.locator('#token-host .tok-bar').count();
  assert.ok(barCount > 0, '每分钟柱一根都没画出来');

  // ── 深链：URL hash 直接落在子块上（首帧必须不炸）────────────────────────
  await page.goto(MONITOR_URL + '#acx-runs');
  await page.waitForFunction(() => {
    const b = document.querySelector('[data-sub="acx-runs"]');
    return !!b && b.classList.contains('active');
  });
  assert.ok((await waitHost('acx-host', 'ACX · 运行')).includes('ACX · 运行'));
  assert.ok(!(await page.locator('#page-body').innerText()).includes('undefined'));

  await openSub('taskchain', 'chainbody');
  await page.screenshot({ path: process.env.MONITOR_SCREENSHOT || 'rdd-monitor-verified.png' });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  assert.deepEqual(errors, []);
  console.log('Browser PASS: two-level nav (6 zones / 23 subs), real task chain, assets/branches/gaps, all formerly blank hosts render (chain/explib/expchain/acx/token), token rate + ledger + context water, deep link by hash, persistent expansion, mobile width, no JS errors');
} finally { await browser.close(); }