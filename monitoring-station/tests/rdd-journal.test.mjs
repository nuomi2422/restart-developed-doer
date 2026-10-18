import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, appendFile, rm, rename } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRddJournal, observationStatus } from '../rdd-journal.mjs';

const row = (type, id, data = {}) => JSON.stringify({ event_id: id, type, timestamp: '2026-09-13T05:00:00Z', data: { companionId: 'a', ...data } }) + '\n';
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'rdd-journal-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return join(dir, 'rdd.jsonl');
}
test('planning context survives a large snapshot stream and fresh reader restart', async t => {
  const file = await fixture(t);
  const content = row('supervisor_context', 'input', { context: '规划上下文' })
    + Array.from({ length: 400 }, (_, i) => row('subtask_failed', 'failure-' + i)).join('')
    + Array.from({ length: 10000 }, (_, i) => row('taskchain_snapshot', 'snapshot-' + i, { padding: '快照'.repeat(100) })).join('');
  await writeFile(file, content);
  for (let i = 0; i < 2; i++) {
    const reader = createRddJournal(file);
    const [a, b] = await Promise.all([reader.read(), reader.read()]);
    assert.deepEqual(a, b);
    assert.equal(a.events.length, 302);
    assert.equal(a.events[0].data.context, '规划上下文');
    assert.equal(a.events.at(-1).event_id, 'snapshot-9999');
    assert.equal(a.bytesRead, Buffer.byteLength(content));
  }
});
test('incremental reads retain split UTF-8, reject invalid/oversized records, and bound history', async t => {
  const file = await fixture(t);
  const reader = createRddJournal(file, { maxEvents: 2, maxLineBytes: 1024 });
  const bytes = Buffer.from(row('supervisor_input', 'one', { text: '中文' }));
  const split = bytes.indexOf(Buffer.from('中文')) + 1;
  await writeFile(file, bytes.subarray(0, split));
  assert.equal((await reader.read()).events.length, 0);
  await appendFile(file, bytes.subarray(split));
  assert.equal((await reader.read()).events[0].data.text, '中文');
  await appendFile(file, 'bad\nnull\n' + 'x'.repeat(2048));
  assert.equal((await reader.read()).invalid, 3);
  await appendFile(file, '\n' + row('subtask_failed', 'two') + row('subtask_failed', 'three') + row('subtask_failed', 'four'));
  const result = await reader.read();
  assert.equal(result.invalid, 3);
  assert.deepEqual(result.events.map(e => e.event_id), ['one', 'three', 'four']);
});
test('removal survives later snapshots of another companion and rotation clears old state', async t => {
  const file = await fixture(t);
  await writeFile(file, row('taskchain_snapshot', 'a', { reason: 'task_removed' }) + row('taskchain_snapshot', 'b', { companionId: 'b' }));
  const reader = createRddJournal(file);
  assert.equal((await reader.read()).events[0].data.reason, 'task_removed');
  await rename(file, file + '.old');
  assert.equal((await reader.read()).ok, false);
  await writeFile(file, row('supervisor_input', 'new'));
  assert.deepEqual((await reader.read()).events.map(e => e.event_id), ['new']);
  await writeFile(file, '');
  assert.deepEqual((await reader.read()).events, []);
});
test('health distinguishes absent, stale, current and future observations', () => {
  const now = Date.parse('2026-09-13T05:00:00Z');
  const event = offset => ({ type: 'taskchain_snapshot', timestamp: new Date(now + offset).toISOString() });
  assert.equal(observationStatus([], now).state, 'NO_DATA');
  assert.equal(observationStatus([event(-31000)], now).state, 'STALE');
  assert.equal(observationStatus([event(-1000)], now).state, 'RECENT');
  assert.equal(observationStatus([event(1000)], now).state, 'CLOCK_SKEW');
});
