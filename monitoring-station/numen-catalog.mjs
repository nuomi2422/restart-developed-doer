import { readFile } from 'node:fs/promises';

// Only initialize and tools/list are permitted here. This endpoint cannot
// execute a body tool or enqueue a model request. Credentials stay server-side.
export function createToolCatalog(configFile) {
  let cached, checkedAt = 0, flight;
  async function read() {
    let session, url, headers;
    try {
      const config = JSON.parse((await readFile(configFile, 'utf8')).replace(/^\uFEFF/, ''));
      if (!config.enabled) return { ok: false, error: 'MCP_DISABLED' };
      if (!['127.0.0.1', 'localhost', '::1'].includes(config.host) || !Number.isInteger(config.port) || config.port < 1 || config.port > 65535) return { ok: false, error: 'INVALID_LOCAL_MCP_ADDRESS' };
      url = `http://${config.host === '::1' ? '[::1]' : config.host}:${config.port}/mcp`;
      headers = { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' };
      async function rpc(method, params, notification = false) {
        const response = await fetch(url, { method: 'POST', headers: { ...headers, ...(session ? { 'Mcp-Session-Id': session } : {}) }, body: JSON.stringify({ jsonrpc: '2.0', ...(notification ? {} : { id: method }), method, params }), signal: AbortSignal.timeout(5000), redirect: 'error' });
        if (!response.ok) throw new Error(`MCP_HTTP_${response.status}`);
        session = response.headers.get('Mcp-Session-Id') || session;
        const text = await response.text();
        if (notification) return;
        const data = response.headers.get('content-type')?.includes('text/event-stream')
          ? text.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => JSON.parse(line.slice(5).trim())).find(value => value.id === method)
          : JSON.parse(text);
        if (!data || data.error) throw new Error('MCP_RPC_FAILED');
        return data.result;
      }
      await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'rdd-monitor-readonly', version: '2' } });
      await rpc('notifications/initialized', undefined, true);
      const tools = [], cursors = new Set();
      let cursor;
      do {
        const result = await rpc('tools/list', cursor ? { cursor } : {});
        if (!Array.isArray(result?.tools)) throw new Error('INVALID_TOOL_CATALOG');
        tools.push(...result.tools);
        cursor = result.nextCursor;
        if (cursor && (cursors.has(cursor) || cursors.size >= 20)) throw new Error('INVALID_CATALOG_PAGINATION');
        cursors.add(cursor);
      } while (cursor);
      return { ok: true, source: 'numen-mcp-tools-list', observedAt: new Date().toISOString(), tools };
    } catch (error) {
      // Do not return provider configuration, tokens, or arbitrary remote text.
      return { ok: false, error: /^MCP_|^INVALID_/.test(error.message) ? error.message : 'MCP_UNAVAILABLE' };
    } finally {
      if (session) await fetch(url, { method: 'DELETE', headers: { ...headers, 'Mcp-Session-Id': session }, signal: AbortSignal.timeout(1000), redirect: 'error' }).catch(() => {});
    }
  }
  return { read() {
    if (cached && Date.now() - checkedAt < 30000) return Promise.resolve(cached);
    if (!flight) flight = read().then(result => { cached = result; checkedAt = Date.now(); return result; }).finally(() => { flight = null; });
    return flight;
  } };
}
