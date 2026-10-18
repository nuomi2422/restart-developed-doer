// mc-watch.js -- one-screen report for "is the companion actually working, and why not".
//
// Replaces the PowerShell one-liners that kept dying on GBK-mojibake and substring
// errors. Everything is parsed in node; output is ASCII so it never mangles.
//
// Usage:
//   node mc-watch.js                 -> status + liveness + last tools + events
//   node mc-watch.js --move          -> also sample movement 8x4s (travel/still/reversals)
//   node mc-watch.js --talk          -> also show the companion's own last narration lines
//   node mc-watch.js --shot          -> also fly to the companion and screenshot
const { execFileSync, spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const NODE = 'D:\\nodejs\\node.exe';
const NCLI = 'E:\\restart developing doer\\rdd-selfcompile\\scripts\\numen-mcp.js';
const MON = 'E:\\.minecraft\\versions\\The Best of Twilight Forest\\config\\numen\\monitor';
const OC = 'E:\\restart developing doer\\mc-openclaw-controller\\scripts';
const argv = process.argv.slice(2);
const has = (n) => argv.includes('--' + n);

function mcp(tool, json) {
  const args = [NCLI, 'call', tool];
  if (json) args.push(json);
  try {
    return JSON.parse(execFileSync(NODE, args, { encoding: 'utf8', timeout: 30000 }).replace(/^\uFEFF/, '').trim());
  } catch (e) { return { __err: String(e.message || e).slice(0, 80) }; }
}

function readTail(file, n) {
  try {
    const txt = fs.readFileSync(file, 'utf8');
    return txt.split('\n').filter(Boolean).slice(-n);
  } catch { return []; }
}

function jsonlTail(file, n) {
  const out = [];
  for (const line of readTail(file, n * 3)) {
    try { out.push(JSON.parse(line)); } catch { /* skip */ }
  }
  return out.slice(-n);
}

const out = { when: new Date().toISOString().slice(11, 19) };

// 1) status
const st = mcp('rdd_status', 'rdd');
if (st.__err || !st.data) out.status = st.__err || 'no data';
else {
  const d = st.data;
  out.status = {
    subtask: d.subtask, prim: d.primary_status, sub: d.subtask_status,
    cond: d.condition_matches, condDef: d.condition, assets: d.assets,
    groups: d.condition_groups,
  };
}

// 2) liveness: tools.jsonl is the honest signal (ai.jsonl keeps ticking without actions)
try {
  const mt = fs.statSync(path.join(MON, 'tools.jsonl')).mtime;
  out.toolsIdleMin = +((Date.now() - mt.getTime()) / 60000).toFixed(1);
} catch { out.toolsIdleMin = null; }
const tools = jsonlTail(path.join(MON, 'tools.jsonl'), 8).map(e => ({
  at: String(e.timestamp || '').slice(11, 19), tool: e.data && e.data.tool,
}));
out.lastTools = tools;

// 3) events (skip the 5Hz snapshot noise)
const evs = jsonlTail(path.join(MON, 'rdd.jsonl'), 400)
  .filter(e => e.type && !/snapshot/.test(e.type));
const counts = {};
for (const e of evs) counts[e.type] = (counts[e.type] || 0) + 1;
out.events = counts;

// 4) inventory highlights (fuel / food / gear matter for long tasks)
const inv = mcp('rdd_get_inventory', 'rdd');
if (inv && inv.data && inv.data.items) {
  const items = inv.data.items;
  const pick = ['minecraft:coal', 'minecraft:jungle_log', 'minecraft:oak_log', 'minecraft:jungle_planks',
    'minecraft:beef', 'minecraft:cooked_beef', 'minecraft:bread', 'minecraft:iron_ingot',
    'minecraft:cobblestone', 'minecraft:stick'];
  out.kit = {};
  for (const k of pick) if (items[k]) out.kit[k.replace('minecraft:', '')] = items[k];
}

// 5) optional: movement sample
if (has('move')) {
  const rows = [];
  for (let i = 0; i < 8; i++) {
    const s = mcp('get_self_status', 'rdd');
    if (s && s.position) rows.push(s.position);
    spawnSync(NODE, ['-e', 'setTimeout(()=>{},4000)']);
  }
  if (rows.length > 1) {
    let travel = 0, still = 0;
    const dirs = [];
    for (let i = 1; i < rows.length; i++) {
      const dx = rows[i].x - rows[i - 1].x, dz = rows[i].z - rows[i - 1].z;
      const d = Math.hypot(dx, dz); travel += d;
      if (d < 0.6) still++;
      else dirs.push(['E', 'SE', 'S', 'SW', 'W', 'NW', 'N', 'NE'][Math.round(Math.atan2(dx, dz) / (Math.PI / 4) + 8) % 8]);
    }
    let rev = 0;
    for (let i = 1; i < dirs.length; i++) {
      const a = ['E', 'SE', 'S', 'SW', 'W', 'NW', 'N', 'NE'];
      const diff = Math.abs(a.indexOf(dirs[i]) - a.indexOf(dirs[i - 1]));
      if (Math.min(diff, 8 - diff) >= 3) rev++;
    }
    out.move = { travelBlocks: +travel.toFixed(1), stillIntervals: still, reversals: rev, path: dirs.join('>') };
    out.aiPos = rows[rows.length - 1];
  }
}

// 6) optional: the companion's own words (highest-yield bug source)
if (has('talk')) {
  const r = spawnSync(NODE, ['E:\\restart developing doer\\rdd-selfcompile\\scripts\\ai-talk.js', '8'], { encoding: 'utf8' });
  try {
    const j = JSON.parse((r.stdout || '{}').replace(/^\uFEFF/, ''));
    out.talk = (j.lines || []).slice(-3).map(l => l.s.slice(0, 160));
  } catch { out.talk = 'parse fail'; }
}

// 7) optional: eyes
if (has('shot')) {
  const r = spawnSync(NODE, [path.join(OC, 'watch-ai.js'), '--from-rdd', '--dist', '6'], { encoding: 'utf8' });
  try {
    const j = JSON.parse((r.stdout || '{}').replace(/^\uFEFF/, ''));
    out.shot = { dist: j.dist, file: 'C:\\Users\\lenovo\\AppData\\Local\\Temp\\opencode\\ai-watch.png' };
  } catch { out.shot = 'capture failed'; }
}

console.log(JSON.stringify(out, null, 1));
