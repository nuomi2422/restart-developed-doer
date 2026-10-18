import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import vm from 'node:vm';
import { createExperimentStatsView } from '../experiment-stats.mjs';

// ============================================================
// ⑥ 实验统计（2026-10-05 新增）
// ------------------------------------------------------------
// 这一页的难处全在**归属**上，所以断言也全盯归属：
//   · 有同伴字段的事件 ⇒ 归属是事实
//   · 没有同伴字段的事件（AC / 经验新增）⇒ 归属只能是时间线推断，
//     而且推断失败时（多只同时开着 / 一只都没开）**不许硬塞给谁**
//   · 分母为 0 的比率 ⇒ null，不是 0
//   · 数据源读不到 ⇒ 前端显示「未接入」，不是 0
// ============================================================

const MIN = 60000;
const T0 = Date.now();          // ★ 用真实 now（freshness 内部按 Date.now 判年龄）
const iso = (ms) => new Date(ms).toISOString();
const at = (minutesAgo) => iso(T0 - minutesAgo * MIN);

function ev(type, minutesAgo, data) {
  return JSON.stringify({ schema_version: 1, timestamp: at(minutesAgo), source: 'numen', type, data });
}
/** taskchain_snapshot 的 taskChain 是 **JSON 字符串**，不是对象 —— 夹具必须照抄这一点。 */
function snap(minutesAgo, cid, goalId, description) {
  return ev('taskchain_snapshot', minutesAgo, {
    companionId: cid,
    reason: 'periodic_observation',
    taskChain: JSON.stringify({ goalId, description, primaryStatus: 'ACTIVE', currentSubtaskId: goalId + '-s0' }),
  });
}

const A = 'aaaaaaaa-1111-1111-1111-111111111111';
const B = 'bbbbbbbb-2222-2222-2222-222222222222';

/**
 * 两只 AI、四个目标段。
 *
 * 时刻表（分钟前）：
 *   -120  A 开 g1        -110  B 开 g2
 *   -100  A 有活动（死亡/工具/回合）
 *   -99   B 有活动（死亡/工具）+ 一条 AC 事件
 *         ⇒ 那一刻两只的目标段都开着；而「谁刚活动过」两个都是 -100（并列）
 *         ⇒ 弱推断也不成立 ⇒ 必须判「无法判定」（这条断言专门盯它）
 *   -50   A 换 g3、B 换 g4（两段各自关闭）
 *   -41   A 有一批活动
 *   -40   AC 事件 + 经验新增
 *         ⇒ 两只的目标段仍然都开着，但 A 在 -41 刚活动过、领先 B 整整 58 分钟
 *         ⇒ 弱推断归给 A 的 g3
 *
 * ★ 注意这里刻意让「AC 事件」与「A 的活动」**不同分钟**：推断用的是
 *   「那一刻**之前**最近一次活动」，同一时刻的活动不算进去（否则会自证）。
 */
async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'rdd-exp-'));
  const mon = join(root, 'monitor');
  await mkdir(mon, { recursive: true });

  await writeFile(join(mon, 'rdd.jsonl'), [
    snap(120, A, 'g1', '目标一'),
    snap(110, B, 'g2', '目标二'),
    // 两只各换目标 ⇒ 各自上一段在这里关闭
    snap(50, A, 'g3', '目标三'),
    snap(50, B, 'g4', '目标四'),
  ].join('\n') + '\n');

  await writeFile(join(mon, 'instrumentation.jsonl'), [
    ev('death', 100, { companionId: A, task: '', deathCauseMsg: 'AAA被骷髅射杀', deathKind: 'mob', deathAttacker: '骷髅' }),
    ev('death', 99, { companionId: B, task: '', deathCauseMsg: 'BBB被骷髅射杀', deathKind: 'mob', deathAttacker: '骷髅' }),
    ev('death', 41, { companionId: A, task: '', deathKind: 'other', deathCauseMsg: '' }),
    ev('loop_detected', 41, { companionId: A, task: '', reason: '同一动作重复 5 次' }),
  ].join('\n') + '\n');

  await writeFile(join(mon, 'tools.jsonl'), [
    ev('tool_call', 100, { companion_id: A, tool: 'goto', args_chars: 20 }),
    ev('tool_rejected', 100, { companion_id: A, tool: 'mine', reason: '工具未取回' }),
    ev('tool_call', 99, { companion_id: B, tool: 'goto', args_chars: 20 }),
  ].concat(Array.from({ length: 10 }, () => ev('tool_call', 41, { companion_id: A, tool: 'goto' })))
    .join('\n') + '\n');

  await writeFile(join(mon, 'events.jsonl'),
    ev('task_finished', 41, { companion_id: A, task: 'collect_items', status: 'done', message: 'ok' }) + '\n');
  await writeFile(join(mon, 'ai.jsonl'), [
    ev('turn', 100, { companion_id: A, turn: 1, convo_msgs: 3, tools: 12 }),
    ev('turn', 41, { companion_id: A, turn: 2, convo_msgs: 5, tools: 12 }),
    ev('turn', 41, { companion_id: A, turn: 3, convo_msgs: 7, tools: 12 }),
    ev('turn', 41, { companion_id: A, turn: 4, convo_msgs: 9, tools: 12 }),
  ].join('\n') + '\n');

  await writeFile(join(mon, 'acx.jsonl'), [
    // ★ 这一条落在「两只的目标段都开着、且最近活动并列」的时刻 ⇒ 必须判无法判定
    ev('acx_event', 99, { kind: 'RUN_STARTED', run_id: 'r-amb', ac_name: 'ambiguous_script' }),
    ev('acx_event', 99, { kind: 'STEP_STARTED', run_id: 'r-amb', step_id: 's1', block: 'goto' }),
    // 这几条落在「两只都开着、但 A 刚活动过、领先 58 分钟」的时刻 ⇒ 弱推断归给 A 的 g3
    ev('acx_event', 40, { kind: 'RUN_STARTED', run_id: 'r-ok', ac_name: 'mine_script' }),
    ev('acx_event', 40, { kind: 'STEP_STARTED', run_id: 'r-ok', step_id: 's1', block: 'mine' }),
    ev('acx_event', 40, { kind: 'STEP_FAILED', run_id: 'r-ok', step_id: 's1', status: 'FAIL', reason: '没有支撑方块' }),
    ev('acx_event', 40, { kind: 'RUN_FINISHED', run_id: 'r-ok', status: 'FAIL' }),
  ].join('\n') + '\n');

  await writeFile(join(mon, 'expmem.jsonl'), [
    ev('recalled', 41, { companion: A, hits: 2, query_chars: 40 }),
    // ★ learned 没有同伴字段（实测键集 id/title/maturity/type/seven_filled）
    ev('learned', 40, { id: 'm1', title: '别在无甲时硬打', maturity: 'confirmed', type: 'FAILURE', seven_filled: 7 }),
  ].join('\n') + '\n');

  await writeFile(join(mon, 'learner.jsonl'),
    ev('reviewed', 41, { companion: A, reviewed: 2, verdicts: 2, committed: 1, queue_depth: 0 }) + '\n');

  return { root, mon };
}

function rowOf(d, cid, goalKey) {
  const r = (d.rows || []).find((x) => x.companionId === cid && x.goalKey === goalKey);
  assert.ok(r, '应该有 ' + cid.slice(0, 6) + ' × ' + goalKey + ' 这一行');
  return r;
}

test('每只 AI 在每个目标下各自成行，行动与死亡按目标分段计数', async () => {
  const { root, mon } = await fixture();
  try {
    const d = await createExperimentStatsView(mon).all();
    assert.equal(d.ok, true);
    // ① 目标按 goalId 切段 ⇒ 四个（AI × 目标）组合就是四行
    assert.equal(d.rows.length, 4, '四段目标 ⇒ 四行（哪怕其中一段一个记录都没有）');
    assert.deepEqual(
      d.rows.map((r) => r.goalKey).sort(),
      ['g1', 'g2', 'g3', 'g4']);

    // ② 死亡/行动按「那一刻该 AI 正在跑的目标」归属
    const a1 = rowOf(d, A, 'g1');
    assert.equal(a1.counts.deaths, 1, 'A 在目标一里死了 1 次');
    assert.equal(a1.counts.actions, 1, 'A 在目标一里只有 1 次工具调用');
    assert.equal(a1.counts.rejected, 1);
    const b2 = rowOf(d, B, 'g2');
    assert.equal(b2.counts.deaths, 1);
    assert.equal(b2.counts.actions, 1);

    const a3 = rowOf(d, A, 'g3');
    assert.equal(a3.counts.deaths, 1);
    assert.equal(a3.counts.actions, 10, '目标三里 A 调了 10 次工具');
    assert.equal(a3.counts.turns, 3);
    assert.equal(a3.counts.tasksDone, 1);
    assert.equal(a3.counts.recalls, 1);
    assert.equal(a3.counts.reviews, 1);
    assert.equal(a3.counts.loops, 1);

    // ③ 派生比率：10 次行动死 1 次 ⇒ 每百次 10
    assert.equal(a3.derived.deathsPer100Actions, 10);
    // ★ 分母为 0 ⇒ null，**不许是 0**（「没行动过所以死亡率 0」是假的）
    const b4 = rowOf(d, B, 'g4');
    assert.equal(b4.counts.actions, 0);
    assert.equal(b4.derived.deathsPer100Actions, null, '行动为 0 时死亡率必须是 null');
    assert.equal(b4.derived.actionsPerTurn, null);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('AC 与经验新增的归属是推断；推断不出来时单列，绝不硬塞给某只 AI', async () => {
  const { root, mon } = await fixture();
  try {
    const d = await createExperimentStatsView(mon).all();

    // ① 多只开着、但某一只刚活动过且领先远超阈值 ⇒ 弱推断归给它
    const a3 = rowOf(d, A, 'g3');
    assert.equal(a3.counts.acRuns, 1, '领先明显的那次 AC 调用要进 A 的目标三');
    assert.equal(a3.counts.acSteps, 1);
    assert.equal(a3.counts.acStepFails, 1);
    assert.equal(a3.derived.stepFailRate, 100, '1 步全失败 = 100%');
    // 归属来源必须分档，不许与「事件自带」混为一谈
    assert.equal(a3.attribution.inferred_leader, 4, 'AC 运行 + 失败步骤 + 经验新增 + 起步那一步 = 4 条弱推断');
    assert.equal(a3.attribution.inferred_single, 0, '那一刻不是「唯一打开」，所以不该算强推断');
    assert.equal(a3.attribution.ambiguous, 0);
    assert.deepEqual(a3.acNames, { mine_script: 1 });

    // ② ★ 刚好卡在阈值上（领先 60 秒）⇒ 必须判无法判定，不许硬塞给谁
    assert.equal(rowOf(d, A, 'g1').counts.acRuns, 0, '歧义时刻不许塞给 A');
    assert.equal(rowOf(d, B, 'g2').counts.acRuns, 0, '歧义时刻不许塞给 B');
    assert.equal(d.ac.ambiguousEvents, 2, 'RUN_STARTED + STEP_STARTED 两条都记为无法判定');
    assert.equal(d.ac.unattributedEvents, 0);
    assert.equal(d.ac.inferredLeader, 4);
    assert.equal(d.ac.leadThresholdMs, 60000);

    // ③ 页面必须能说清这几条口径
    const ids = d.caveats.map((c) => c.id);
    assert.ok(ids.includes('ac-attribution-inferred'), '必须说清 AC 归属是推断');
    assert.ok(ids.includes('death-task-empty'), '必须说清死亡自带的目标字段是空的');
    assert.ok(ids.includes('two-ac-journals'), '必须说清两个 AC 日志不可相加');

    // ④ 经验新增也没有同伴字段 ⇒ 同样按推断归属，且标题/成熟度要能看到
    assert.equal(a3.counts.memosLearned, 1);
    assert.deepEqual(a3.learnedMaturity, { confirmed: 1 });

    // ⑤ 两个 AC 日志不许相加：ac.jsonl 不存在时 acJournalEvents 必须是 0 而不是编一个
    assert.equal(d.ac.journalEvents, 0);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('一个记录都没有的目标也要成行，否则「没动作」和「没这个目标」长得一样', async () => {
  const { root, mon } = await fixture();
  try {
    const d = await createExperimentStatsView(mon).all();
    // B 换了 g4 之后什么也没做 ⇒ 仍然要有这一行，且全是 0
    const b4 = rowOf(d, B, 'g4');
    assert.equal(b4.counts.deaths, 0);
    assert.equal(b4.counts.actions, 0);
    assert.equal(b4.goalLabel, '目标四', '目标名要显示出来，不能只给一个 uuid');
    // 分母为 0 的派生值是 null（页面显示 ?），不是 0
    assert.equal(b4.derived.deathsPer100Actions, null);
    // 这一行没有事件 ⇒ 覆盖率里要算作「目标未接入」之外的情况：
    // 它有目标，只是没记录，所以 unknown_goal 计数不受它影响
    assert.equal(d.goalCoverage.companions_with_goals, 2);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('没有任何目标快照的时间不许被当成「某个目标下的表现」', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rdd-exp-nogoal-'));
  try {
    const mon = join(root, 'monitor');
    await mkdir(mon, { recursive: true });
    // 只有工具调用，没有任何 taskchain_snapshot ⇒ 目标结构上就不知道
    await writeFile(join(mon, 'tools.jsonl'), [
      ev('tool_call', 10, { companion_id: A, tool: 'goto' }),
      ev('tool_call', 9, { companion_id: A, tool: 'mine' }),
    ].join('\n') + '\n');
    await writeFile(join(mon, 'rdd.jsonl'), ev('planning_knowledge', 10, { companionId: A, injected: true }) + '\n');

    const d = await createExperimentStatsView(mon).all();
    assert.equal(d.rows.length, 1);
    assert.equal(d.rows[0].goalKey, d.unknownGoal.key, '必须落在「目标未记录」这一档');
    assert.equal(d.rows[0].goalSource, 'unknown');
    assert.equal(d.rows[0].counts.actions, 2);

    // ★ 目标覆盖率必须是一个显式读数，而不是让读者自己从一堆「（目标未记录）」里猜
    const gc = d.goalCoverage;
    assert.equal(gc.companions_total, 1);
    assert.equal(gc.companions_with_goals, 0, '没快照 ⇒ 一只都没有目标');
    assert.equal(gc.rows_unknown_goal, 1);
    assert.equal(gc.events_in_unknown_goal, 2);
    assert.equal(gc.unknown_percent, 100);
    assert.ok(d.caveats.some((c) => c.id === 'goal-unknown-bucket'), '必须说明这一行不是「某个目标下的表现」');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('曲线是连续桶 + 明确标出 gap，不补空桶也不假装连续', async () => {
  const { root, mon } = await fixture();
  try {
    const d = await createExperimentStatsView(mon).all();
    const s = d.series;
    assert.ok(s.buckets.length > 1, '横轴要有多个桶才叫曲线');
    // 最早记录 -100 分、最晚 -40 分 ⇒ 跨 60 分钟。
    // 每格 1 分钟要 61 格 > 上限 48 ⇒ 必须升到 5 分钟一格（12 格）。
    // 这条断言的作用是锁住「桶宽按跨度自动选」，否则曲线要么挤成一团要么被砍掉。
    assert.equal(s.bucketMs, 300000, '桶宽必须按跨度自动放大');

    const a3 = s.rows.find((r) => r.goalKey === 'g3');
    assert.ok(a3, '目标三要有序列');
    assert.ok(a3.cells.actions, '行动次数要进序列');
    const idxs = Object.keys(a3.cells.actions).map(Number);
    assert.equal(idxs.length, 1, '同一时刻的 10 次调用落在同一个桶里');
    assert.equal(a3.cells.actions[String(idxs[0])], 10);
    // ★ gap 数 = 总桶数 − 有数据的桶数，必须报出来（不能让人以为空白都是 0）
    assert.equal(a3.gaps, s.buckets.length - a3.bucketsWithData);
    assert.equal(s.pointsOutsideRange, 0, '夹具里没有事件落在网格外');
    // 服务端内部原料不许外泄（points 每个事件一个对象，会把 payload 撑大一个数量级）
    assert.equal(a3.points, undefined, '序列原料 points 不许出现在响应里');
    for (const r of d.rows) assert.equal(r.points, undefined, '行里也不许带 points');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('学习曲线用行动次数当分母，头尾有一段没行动时趋势是 null 而不是「持平」', async () => {
  const { root, mon } = await fixture();
  try {
    const d = await createExperimentStatsView(mon).all();
    const L = d.learning;
    assert.equal(L.slices, 4);
    const a3 = L.rows.find((r) => r.goalKey === 'g3');
    assert.ok(a3, '目标三行动够多，应该进学习曲线');
    assert.equal(a3.slices.reduce((n, s) => n + s.actions, 0), 10, '四段的行动加起来要等于总数');
    assert.equal(a3.slices.reduce((n, s) => n + s.deaths, 0), a3.totalDeaths);
    // 只在第 1 段死过一次 ⇒ 后 3 段每百次行动是 0，第 1 段 ≥ 0 ⇒ 趋势为负或 0
    assert.ok(a3.trend <= 0, '后段死得少 ⇒ 趋势不为正，实测 ' + a3.trend);

    // ★ 行动不足 8 次的行不进学习曲线（切 4 段没意义），页面上是「还不够」而不是 0
    assert.ok(!L.rows.some((r) => r.goalKey === 'g4'), '只有 1 次行动的 B/目标四不该进学习曲线');
    assert.ok(L.note_zh.includes('分母用行动次数'), '必须说清分母是行动次数而不是时长');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('数据源读不到时：那一份指标标成未接入，且空原因点名到它', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rdd-exp-gone-'));
  try {
    const mon = join(root, 'monitor');
    await mkdir(mon, { recursive: true });
    // ★ 故意不建 acx.jsonl / instrumentation.jsonl
    await writeFile(join(mon, 'rdd.jsonl'), snap(100, A, 'g1', '目标一') + '\n');
    await writeFile(join(mon, 'tools.jsonl'), ev('tool_call', 10, { companion_id: A, tool: 'goto' }) + '\n');

    const d = await createExperimentStatsView(mon).all();
    const byKey = Object.fromEntries(d.coverage.sources.map((s) => [s.key, s]));
    assert.equal(byKey.acx.readable, false, 'acx 不存在 ⇒ readable=false（不是「0 条」）');
    assert.equal(byKey.acx.eventsRead, 0);
    assert.equal(byKey.instrumentation.readable, false);
    assert.equal(byKey.tools.readable, true);

    // 「读不到」必须出现在空原因里，否则页面会静悄悄地显示 0
    assert.ok(d.emptyReasons.some((x) => x.includes('acx.jsonl')), '空原因要点名 acx.jsonl');
    assert.ok(d.emptyReasons.some((x) => x.includes('instrumentation.jsonl')), '空原因要点名 instrumentation.jsonl');

    // 总新鲜度条取「最差的一份」⇒ 只要有一份读不到就必须是 unreadable
    assert.equal(d.freshness.verdict, 'unreadable');
    assert.ok(d.freshness.per_source.some((x) => x.key === 'acx' && x.readable === false));

    // 仍然读得到的那个源照常给数（不能因为一份坏了就整页不显示）
    assert.equal(d.rows[0].counts.actions, 1);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('行的时段必须是 ISO 字符串，不能是毫秒数', async () => {
  const { root, mon } = await fixture();
  try {
    const d = await createExperimentStatsView(mon).all();
    // ★ 这条锁的是一个已经踩过的坑：服务端内部用 ms 比大小，顺手就把 ms 放进
    //   firstAt/lastAt 发出去了，而前端是按 ISO 字符串显示的 ——
    //   于是交叉表里出现「54169 ?」这种既不是时间也不是错误的东西。
    //   本工程对外一律给 ISO（其它 view 都是这样），所以在这里钉死。
    for (const r of d.rows) {
      assert.equal(typeof r.firstAt, r.firstMs == null ? 'object' : 'string',
        'firstAt 必须是 ISO 字符串（firstMs 才是毫秒数）');
      if (r.firstMs != null) {
        assert.match(r.firstAt, /^\d{4}-\d\d-\d\dT[\d:.]+Z$/, 'firstAt 要是 ISO-8601');
        assert.match(r.lastAt, /^\d{4}-\d\d-\d\dT[\d:.]+Z$/, 'lastAt 要是 ISO-8601');
        assert.ok(r.firstMs <= r.lastMs, '内部毫秒数也要自洽');
      }
    }
    assert.match(d.coverage.firstEventAt, /^\d{4}-\d\d-\d\dT/);
    assert.match(d.coverage.lastEventAt, /^\d{4}-\d\d-\d\dT/);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('页面：时段列显示得出真实时间，不能是「54169 ?」这种半成品', async () => {
  const { root, mon } = await fixture();
  try {
    const d = await createExperimentStatsView(mon).all();
    const { html } = await loadView(d);
    // 「MM-DD HH:MM」这一形态必须出现
    assert.match(html, /\d\d-\d\d \d\d:\d\d/, '时段列要显示出可读的时间');
    assert.ok(!html.includes('? ~'), '两个 ? 相邻 = 时间没渲染出来');
    // 序列的 X 轴首尾也要有时间
    assert.match(html, /\d\d-\d\d \d\d:\d\d<\/text>/, 'X 轴刻度要有时间');
  } finally { await rm(root, { recursive: true, force: true }); }
});

// ============================================================
// 前端：把真实聚合喂进沙箱里的 experiment-view.js
// ============================================================
async function loadView(payload) {
  const src = await readFile(new URL('../js/experiment-view.js', import.meta.url), 'utf8');
  const host = { innerHTML: '' };
  const store = {};
  const box = vm.createContext({
    document: {
      getElementById: (id) => (id === 'experiment-host' ? host : null),
      addEventListener() {},
    },
    window: {},
    // localStorage 在真浏览器里有，在沙箱里没有 —— view 必须自己兜住
    localStorage: {
      getItem: (k) => (k in store ? store[k] : null),
      setItem: (k, v) => { store[k] = String(v); },
      removeItem: (k) => { delete store[k]; },
    },
    setInterval: () => 0,
    fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(payload) }),
  });
  vm.runInContext(src, box);
  await box.window.ExperimentView.load();
  return { html: host.innerHTML, box, host };
}

test('页面：面板台、曲线、交叉表都在，读不到的格子写「未接入」而不是 0', async () => {
  const { root, mon } = await fixture();
  try {
    const d = await createExperimentStatsView(mon).all();
    const { html, box } = await loadView(d);

    assert.ok(html.includes('实验统计 · 每个 AI 在每个目标下的表现'), '页标题要出');
    assert.ok(html.includes('AI × 目标 交叉表'), '交叉表要出');
    assert.ok(html.includes('exp-dock'), '面板台要出');
    assert.ok(html.includes('exp-chart'), '曲线要出（纯 SVG，不是图片）');
    assert.ok(html.includes('数据接入情况'), '接入表要出');
    assert.ok(html.includes('学习情况'), '学习曲线要出');
    assert.ok(html.includes('本页口径说明'), '口径说明要出');

    // ★ 「未接入」与「推断」两个标记都要在页面上
    assert.ok(html.includes('推断'), '推断归属必须在页面上被标出来');

    // 输出一旦出现 undefined / NaN，读的人就会以为那是数据
    assert.ok(!html.includes('undefined'), '★ 页面里不许出现 undefined');
    assert.ok(!html.includes('NaN'), '★ 页面里不许出现 NaN');
    assert.ok(!html.includes('[object Object]'), '★ 不许把对象直接插值进 HTML');

    // 每个面板都带自己的三个开关（这就是「可自由调换」）
    for (const id of ['deaths', 'actions', 'ac', 'rate', 'learn']) {
      assert.ok(html.includes('data-exp-panel="' + id + '"'), '面板 ' + id + ' 要出');
      assert.ok(html.includes("data-exp-pmetric='" + id + "'"), '面板 ' + id + ' 要有指标开关');
      assert.ok(html.includes("data-exp-pgroup='" + id + "'"), '面板 ' + id + ' 要有分组开关');
      assert.ok(html.includes("data-exp-ptype='" + id + "'"), '面板 ' + id + ' 要有线/柱开关');
    }
    // 重排的两种手段（拖 + 按钮）都得在
    assert.ok(html.includes('draggable="true"'), '面板头要可拖拽');
    assert.ok(html.includes('data-exp-move="-1"'), '要有上移按钮（拖拽不可用时的兜底）');

    // state 可写：读的人要能在 vm 外检查排法
    assert.ok(box.window.ExperimentView.state.order.length === box.window.ExperimentView.PANELS.length);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('派生比率在曲线上是现算的：比率不能把每行直接相加，分母为 0 的桶要断开', async () => {
  const { root, mon } = await fixture();
  try {
    const d = await createExperimentStatsView(mon).all();
    const { html, box, host } = await loadView(d);
    // 默认配置下「死亡率走势」面板也要能画 —— 它是派生指标，服务端**没有**逐桶序列，
    //   完全靠前端把两个分量按桶加起来再除。第一版这里恒空（面板永远显示「没有可画的记录」）。
    const ratePanel = html.slice(html.indexOf('data-exp-panel="rate"'));
    assert.ok(ratePanel.indexOf('exp-chart-empty') < 0,
      '★ 派生比率必须有曲线，不许永远显示「没有任何可画的记录」');
    assert.ok(ratePanel.indexOf('<svg') > 0, '★ 死亡率面板要出 SVG');
    assert.ok(html.includes('NaN') === false, '★ 比率算错会变成 NaN');
    assert.ok(html.includes('Infinity') === false, '★ 除以 0 不许出现 Infinity');

    // 换成分组/图型后重渲染，宿主内容必须被刷新（而不是停在旧图）
    box.window.ExperimentView.state.panels.rate = { metric: 'stepFailRate', group: 'pair', type: 'bar' };
    box.window.ExperimentView.render();
    assert.notEqual(host.innerHTML, html, '改完面板设置要重画');
    assert.ok(!host.innerHTML.includes('NaN'));
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('页面：AC 数据源读不到时，AC 那几格写「未接入」，且绝不显示 0 次', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rdd-exp-front-'));
  try {
    const mon = join(root, 'monitor');
    await mkdir(mon, { recursive: true });
    await writeFile(join(mon, 'rdd.jsonl'), snap(100, A, 'g1', '目标一') + '\n');
    await writeFile(join(mon, 'tools.jsonl'), ev('tool_call', 10, { companion_id: A, tool: 'goto' }) + '\n');
    // 没有 acx.jsonl / instrumentation.jsonl

    const d = await createExperimentStatsView(mon).all();
    const { html } = await loadView(d);

    assert.ok(html.includes('未接入'), '要明说哪些没接上');
    assert.ok(html.includes('acx-fresh-gone') || html.includes('acx-fresh-bad'), '数据源坏了要红着说');
    // ★ 死因「0」这个数绝对不许出现：读不到就是读不到
    assert.ok(!/死亡[\s\S]{0,80}?<div class="exp-stat-v">0<\/div>/.test(html), '读不到时死亡不许显示 0');
    // 汇总卡里 AC 那张必须写「未接入」
    assert.ok(/AC 调用[\s\S]{0,400}未接入/.test(html), 'AC 汇总卡要写未接入');
    // 但读得到的那个源照常出数（不能整页都不显示）
    assert.ok(html.includes('exp-stat-v">1<'), '读得到的行动次数要照常显示');
    assert.ok(!html.includes('undefined'));
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('页面：坏掉的 localStorage 不能造出空面板', async () => {
  const d = {
    ok: true, metrics: [{ id: 'deaths', label: '死亡', unit: '次', kind: 'bad', source: 'event', hint: '' }],
    companions: [], goals: [], rows: [], series: { buckets: [], rows: [] },
    learning: { slices: 4, rows: [] }, causes: [], ac: {},
    coverage: { sources: [], note_zh: '' }, freshness: { verdict: 'fresh', verdict_zh: 'ok' },
    caveats: [], emptyReasons: [],
  };
  const src = await readFile(new URL('../js/experiment-view.js', import.meta.url), 'utf8');
  const host = { innerHTML: '' };
  const box = vm.createContext({
    document: { getElementById: (id) => (id === 'experiment-host' ? host : null), addEventListener() {} },
    window: {},
    localStorage: {
      getItem: () => '{"order":["不存在面板","deaths"],"panels":{"deaths":{"metric":"没有这个指标","group":"瞎写","type":"pie"}}}',
      setItem() {}, removeItem() {},
    },
    setInterval: () => 0,
    fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(d) }),
  });
  vm.runInContext(src, box);
  box.window.ExperimentView.init();
  // init() 内部的第一次 load 是异步的；这里要等它落地，
  // 否则断言跑在 render 之前 —— 「坏 localStorage 被修正」这件事根本还没发生。
  await new Promise((r) => setImmediate(r));
  await new Promise((r) => setImmediate(r));
  const st = box.window.ExperimentView.state;
  // 未知面板 id 被丢掉，其余面板补回去 ⇒ 面板数不变
  assert.equal(st.order.length, box.window.ExperimentView.PANELS.length, '坏 order 必须被补全');
  assert.ok(!st.order.includes('不存在面板'));
  // ★ 指标必须对当前这份数据校验过：服务端没有「没有这个指标」这一项
  //   ⇒ 面板要退回自己的默认指标，而不是一直盯着一个不存在的东西。
  assert.equal(st.panels.deaths.metric, 'deaths', '不存在的指标必须退回默认值');
  // 非法 group / type 必须退回原值
  assert.ok(st.panels.deaths.type === 'line' || st.panels.deaths.type === 'bar', '非法图型要退回');
  assert.ok(['pair', 'companion', 'goal'].indexOf(st.panels.deaths.group) >= 0, '非法分组要退回');
  assert.ok(!host.innerHTML.includes('undefined'));
});