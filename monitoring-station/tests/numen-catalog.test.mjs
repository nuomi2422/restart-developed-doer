import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createToolCatalog } from '../numen-catalog.mjs';

test('catalog queries only local MCP, retains session, paginates and caches concurrent readers', async t => {
  const calls = [];
  const server = createServer(async (req, res) => {
    if (req.method === 'DELETE') { res.writeHead(204).end(); return; }
    let body = ''; for await (const chunk of req) body += chunk;
    const rpc = JSON.parse(body); calls.push(rpc.method);
    let result;
    if (rpc.method === 'initialize') { res.setHeader('Mcp-Session-Id', 'probe'); result = {}; }
    else {
      assert.equal(req.headers['mcp-session-id'], 'probe');
      assert.equal(req.headers.authorization, 'Bearer dummy-local-test-token');
      if (rpc.method === 'notifications/initialized') { assert.equal(rpc.id, undefined); res.writeHead(202).end(); return; }
      assert.equal(rpc.method, 'tools/list');
      result = rpc.params.cursor ? { tools: [{ name: 'get_self_status' }] } : { tools: [{ name: 'mine' }], nextCursor: 'next' };
    }
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const dir = await mkdtemp(join(tmpdir(), 'rdd-catalog-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const file = join(dir, 'mcp.json');
  await writeFile(file, JSON.stringify({ enabled: true, host: '127.0.0.1', port: server.address().port, token: 'dummy-local-test-token' }));
  const reader = createToolCatalog(file);
  const [a, b] = await Promise.all([reader.read(), reader.read()]);
  assert.deepEqual(a, b); assert.equal(a.ok, true);
  assert.deepEqual(a.tools.map(tool => tool.name), ['mine', 'get_self_status']);
  assert.deepEqual(await reader.read(), a);
  assert.deepEqual(calls, ['initialize', 'notifications/initialized', 'tools/list', 'tools/list']);
  assert.ok(!JSON.stringify(a).includes('dummy-local-test-token'));
});
test('disabled, missing and nonlocal configurations never claim a catalog', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'rdd-catalog-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const file = join(dir, 'mcp.json');
  assert.equal((await createToolCatalog(file).read()).ok, false);
  await writeFile(file, JSON.stringify({ enabled: false }));
  assert.equal((await createToolCatalog(file).read()).error, 'MCP_DISABLED');
  await writeFile(file, JSON.stringify({ enabled: true, host: 'external.invalid', port: 8765 }));
  assert.equal((await createToolCatalog(file).read()).error, 'INVALID_LOCAL_MCP_ADDRESS');
});
