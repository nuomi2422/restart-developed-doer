// ai-talk.js -- mine the companion's own narration for "it is stuck / confused" signals.
//
// The user's method: the AI talks to itself while working; long silence = stuck,
// and weird sentences ("I'll try another way") often point straight at the bug.
// This turns that into a filterable stream instead of watching chat by hand.
//
// Usage:
//   node ai-talk.js [minutes=30]        -> narration from the last N minutes
//   node ai-talk.js 30 --suspicious      -> only lines matching stuck/confused patterns
//   node ai-talk.js 30 --gaps           -> silence gaps between utterances (>45s)
const fs = require('fs');
const path = require('path');
const MON = 'D:\\rdd\\mc\\The Best of Twilight Forest\\config\\numen\\monitor';

const MINS = parseFloat(process.argv[2] || '30');
const SUS = process.argv.includes('--suspicious');
const GAPS = process.argv.includes('--gaps');
const SINCE = Date.now() - MINS * 60 * 1000;

// Stuck / confusion / give-up / retry-with-another-way signals (zh + en).
const PAT = /(卡住|卡在|没反应|不动|无法|不能|失败|找不到|不可达|没有路|无路|不可行|再试|换个|换一条|另一���|没办法|受阻|堵住|挡住|被卡|又走|还在原地|重复|again|retry|stuck|blocked|cannot|unable|no path|timeout)/i;

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (e.name.startsWith('context') && e.name.endsWith('.jsonl')) acc.push(p);
  }
  return acc;
}

function textsOf(obj) {
  const out = [];
  const grab = (v) => {
    if (typeof v === 'string' && v.length > 8) out.push(v);
    else if (Array.isArray(v)) v.forEach(grab);
    else if (v && typeof v === 'object') Object.values(v).forEach(grab);
  };
  grab(obj);
  return out;
}

const rows = [];
for (const f of walk(MON)) {
  let txt;
  try { txt = fs.readFileSync(f, 'utf8'); } catch { continue; }
  for (const line of txt.split('\n')) {
    if (!line || line.length < 40) continue;
    let o; try { o = JSON.parse(line); } catch { continue; }
    const t = o.timestamp ? Date.parse(o.timestamp) : (o.ts || 0);
    if (!t || t < SINCE) continue;
    for (const s of textsOf(o)) {
      // narration only: skip pure json blobs / ids / prompts boilerplate
      if (/^\s*[{[]/.test(s) || s.length < 12) continue;
      rows.push({ t, s: s.replace(/\s+/g, ' ').trim().slice(0, 300), f: path.basename(f) });
    }
  }
}
rows.sort((a, b) => a.t - b.t);

if (GAPS) {
  const gaps = [];
  for (let i = 1; i < rows.length; i++) {
    const d = (rows[i].t - rows[i - 1].t) / 1000;
    if (d > 45) gaps.push({ after: new Date(rows[i - 1].t).toISOString().slice(11, 19), secs: Math.round(d) });
  }
  console.log(JSON.stringify({ windowMin: MINS, utterances: rows.length, gapsOver45s: gaps.length, gaps: gaps.slice(-12) }, null, 1));
  process.exit(0);
}

const picked = SUS ? rows.filter(r => PAT.test(r.s)) : rows;
console.log(JSON.stringify({ windowMin: MINS, total: rows.length, shown: picked.length,
  lines: picked.slice(-Number(process.env.LIMIT || 20)).map(r => ({ at: new Date(r.t).toISOString().slice(11, 19), s: r.s })) }, null, 1));
