import { open } from 'node:fs/promises';

// A derived, in-memory index of the existing journal. Periodic snapshots must
// not evict the planning inputs that explain a long-running task.
export function createRddJournal(file, { maxEvents = 300, maxCompanions = 32, maxBytes = 8 * 1024 * 1024, maxLineBytes = 1024 * 1024 } = {}) {
  const contextTypes = new Set(['supervisor_input', 'supervisor_context', 'numen_context', 'supervisor_output', 'llm_request', 'llm_response', 'llm_failure']);
  let identity, offset = 0, pending = Buffer.alloc(0), skipping = false;
  let events = [], snapshots = new Map(), contexts = new Map(), retainedBytes = 0, sequence = 0, invalid = 0, flight;
  function reset() {
    offset = 0; pending = Buffer.alloc(0); skipping = false;
    events = []; snapshots = new Map(); contexts = new Map(); retainedBytes = 0; sequence = 0; invalid = 0;
  }
  function consume(line) {
    if (!line.length) return;
    let event;
    try { event = JSON.parse(line.toString('utf8')); }
    catch { invalid++; return; }
    if (!event || typeof event !== 'object' || !event.type) { invalid++; return; }
    const entry = { event, bytes: line.length, sequence: sequence++ };
    if (event.type === 'taskchain_snapshot') {
      const id = event.data?.companionId || event.data?.companion_id || 'unknown';
      const previous = snapshots.get(id);
      if (previous) retainedBytes -= previous.bytes;
      snapshots.delete(id);
      snapshots.set(id, entry);
      if (snapshots.size > maxCompanions) {
        const first = snapshots.keys().next().value;
        retainedBytes -= snapshots.get(first).bytes;
        snapshots.delete(first);
      }
    } else if (contextTypes.has(event.type)) {
      const key = `${event.data?.companionId || event.data?.companion_id || 'unknown'}:${event.data?.actor || 'legacy'}:${event.type}`;
      if (contexts.has(key)) retainedBytes -= contexts.get(key).bytes;
      contexts.delete(key); contexts.set(key, entry);
      if (contexts.size > maxCompanions * contextTypes.size) {
        const first = contexts.keys().next().value;
        retainedBytes -= contexts.get(first).bytes; contexts.delete(first);
      }
    } else {
      events.push(entry);
      if (events.length > maxEvents) retainedBytes -= events.shift().bytes;
    }
    retainedBytes += entry.bytes;
    while (retainedBytes > maxBytes && events.length) retainedBytes -= events.shift().bytes;
    while (retainedBytes > maxBytes && contexts.size) {
      const first = contexts.keys().next().value;
      retainedBytes -= contexts.get(first).bytes; contexts.delete(first);
    }
    while (retainedBytes > maxBytes && snapshots.size) {
      const first = snapshots.keys().next().value;
      retainedBytes -= snapshots.get(first).bytes;
      snapshots.delete(first);
    }
  }
  async function refresh() {
    let handle;
    try {
      handle = await open(file, 'r');
      const info = await handle.stat();
      const nextIdentity = `${info.dev}:${info.ino}:${info.birthtimeMs}`;
      if (identity !== nextIdentity || info.size < offset) { reset(); identity = nextIdentity; }
      const buffer = Buffer.alloc(256 * 1024);
      // Use a fixed end per refresh: an active producer cannot keep this read
      // alive forever. Concurrent HTTP polls share this promise.
      while (offset < info.size) {
        const { bytesRead } = await handle.read(buffer, 0, Math.min(buffer.length, info.size - offset), offset);
        if (!bytesRead) break;
        offset += bytesRead;
        const chunk = buffer.subarray(0, bytesRead);
        let start = 0, end;
        while ((end = chunk.indexOf(10, start)) !== -1) {
          const part = chunk.subarray(start, end);
          if (!skipping && pending.length + part.length <= maxLineBytes) consume(Buffer.concat([pending, part]));
          else if (!skipping) invalid++;
          pending = Buffer.alloc(0); skipping = false; start = end + 1;
        }
        if (!skipping) {
          const rest = chunk.subarray(start);
          if (pending.length + rest.length > maxLineBytes) { pending = Buffer.alloc(0); skipping = true; invalid++; }
          else pending = Buffer.concat([pending, rest]);
        }
      }
      return { ok: true, events: [...events, ...snapshots.values(), ...contexts.values()].sort((a, b) => a.sequence - b.sequence).map(e => e.event), invalid, bytesRead: offset };
    } catch (error) {
      // Never present the previous file's cached state as a successful read.
      return { ok: false, events: [], error: error.code || error.message, invalid };
    } finally { await handle?.close(); }
  }
  return { read() { if (!flight) flight = refresh().finally(() => { flight = null; }); return flight; } };
}

export function observationStatus(events, now = Date.now()) {
  const timestamps = events.filter(e => e.type === 'taskchain_snapshot').map(e => Date.parse(e.timestamp)).filter(Number.isFinite);
  const latest = timestamps.length ? Math.max(...timestamps) : null;
  const ageMs = latest === null ? null : now - latest;
  return { state: ageMs === null ? 'NO_DATA' : ageMs < 0 ? 'CLOCK_SKEW' : ageMs <= 30000 ? 'RECENT' : 'STALE', lastObservedAt: latest === null ? null : new Date(latest).toISOString(), ageMs };
}
