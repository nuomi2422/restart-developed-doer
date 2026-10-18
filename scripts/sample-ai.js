// sample-ai.js -- quantify the companion's movement: sample position N times and
// count direction reversals (that is the "left-right oscillation" the user sees).
// Read-only: only calls get_self_status on the Numen MCP.
//
// Usage: node sample-ai.js [samples=14] [intervalMs=5000]
const { execFileSync } = require('child_process');
const N = parseInt(process.argv[2] || '14', 10);
const IV = parseInt(process.argv[3] || '5000', 10);
const CLI = 'E:\\restart developing doer\\rdd-selfcompile\\scripts\\numen-mcp.js';

function pos() {
  try {
    const raw = execFileSync('D:\\nodejs\\node.exe', [CLI, 'call', 'get_self_status', 'rdd'],
      { encoding: 'utf8', timeout: 20000 });
    const j = JSON.parse(raw);
    const p = (j.data && j.data.position) || j.position || {};
    return { x: p.x, y: p.y, z: p.z, t: Date.now() };
  } catch (e) { return null; }
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
(async () => {
  const rows = [];
  for (let i = 0; i < N; i++) { const p = pos(); if (p) rows.push(p); await sleep(IV); }
  if (rows.length < 2) { console.log(JSON.stringify({ error: 'no samples' })); return; }

  // horizontal step direction per interval, quantized to 8 compass sectors
  const dirs = [];
  for (let i = 1; i < rows.length; i++) {
    const dx = rows[i].x - rows[i - 1].x, dz = rows[i].z - rows[i - 1].z;
    const d = Math.hypot(dx, dz);
    const dy = rows[i].y - rows[i - 1].y;
    dirs.push({ i, d: +d.toFixed(2), dy: +dy.toFixed(2),
      s: d < 0.6 ? 'STILL' : ['E','SE','S','SW','W','NW','N','NE'][Math.round(Math.atan2(dx, dz) / (Math.PI / 4) + 8) % 8] });
  }
  let reversals = 0, prevMoving = null, stillCount = 0, travel = 0;
  for (let i = 1; i < dirs.length; i++) {
    travel += dirs[i].d;
    if (dirs[i].s === 'STILL') stillCount++;
    const a = ['E','SE','S','SW','W','NW','N','NE'];
    if (dirs[i].s !== 'STILL' && dirs[i - 1].s !== 'STILL') {
      const ia = a.indexOf(dirs[i].s), ib = a.indexOf(dirs[i - 1].s);
      const diff = Math.min(Math.abs(ia - ib), 8 - Math.abs(ia - ib));
      if (diff >= 3) reversals++;            // >=135 deg = going back
    }
  }
  const bucket = new Map();
  for (const r of rows) {
    const k = `${Math.floor(r.x / 8)},${Math.floor(r.y / 8)},${Math.floor(r.z / 8)}`;
    bucket.set(k, (bucket.get(k) || 0) + 1);
  }
  console.log(JSON.stringify({
    samples: rows.length,
    from: { x: +rows[0].x.toFixed(1), y: +rows[0].y.toFixed(1), z: +rows[0].z.toFixed(1) },
    to: { x: +rows[rows.length - 1].x.toFixed(1), y: +rows[rows.length - 1].y.toFixed(1), z: +rows[rows.length - 1].z.toFixed(1) },
    travelBlocks: +travel.toFixed(1),
    stillIntervals: stillCount,
    reversals,
    distinctBuckets: bucket.size,
    topBucketShare: +(Math.max(...bucket.values()) / rows.length).toFixed(2),
    dirs: dirs.map(d => d.s + (d.d < 0.6 ? '' : '/' + d.d)).join(' '),
  }, null, 1));
})();
