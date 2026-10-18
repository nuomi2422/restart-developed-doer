import { readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';

// ============================================================
// 数据新鲜度 —— 第 5 组红线「历史 ≠ 当前」的读数口（2026-10-04）
// ============================================================
//
// ★ 要解决的误导：页面上一个孤零零的数字（「ACX 跑了 65 次」）读起来
//   永远是「现在的情况」。实测反例：2026-10-04 07:20 那一刻，
//   acx.jsonl 停在 10-03 17:32（13.8 小时前），而 monitor 目录里
//   其它文件停在 10-04 01:56（5.4 小时前）——**两者差了 8.4 小时**。
//   也就是说页上那 65 次是**上一局甚至更早**的，
//   而页面从头到尾没说过这件事。
//
// ★ 三条不许省的边界（写在这里，因为它们决定了这个读数能回答什么）：
//
//   1. **`monitor_newest_write` 不是「本局的开始」**，它是
//      「整个 monitor 目录里最新的 mtime」= **游戏最后一次还在写东西**的时刻。
//      本工程没有可靠的会话开始时间戳（`runtime-guard/session.json`
//      实测会留下几天前的陈旧文件，见既往记录），
//      **所以这里不假装知道本局边界，只报「最后活动」**。
//
//   2. **轮转文件名里的 millis 不是会话开始**。
//      `monitor/` 下有 `context-<millis>.jsonl` 这种轮转文件，
//      那个数字是轮转时刻。取 mtime 最新的那个是对的，
//      但**不能拿它当「这一局从哪开始」**。
//
//   3. **`this_file_write`（mtime）与 `last_event_at`（文件里最后一条记录的时间）
//      是两个不同的东西**，必须都报：
//        - 文件在被写但最后一条事件很旧 ⇒ 「有人在写，但写的不是这类事件」
//        - 文件很旧但里面最后一条事件很新 ⇒ 不可能（mtime 不会早于最后一行）
//      只报一个就会出现「时间对不上但没人知道该信哪个」。
//
// ★ 为什么 `stale` 用**绝对年龄**判定，而不是「别的文件比它新」：
//   第一版用相对量（behind = 目录最新 mtime − 本文件 mtime），实测踩到两个问题：
//     ① 本页这份恰好是目录里最新的一份时，behind 恒为 0 ⇒ stale 永不触发；
//        而「这份数据 13 小时没更新」恰恰是最该告警的情况。
//     ② behind 为 0 时无法区分「刚刚写过」与「13 小时前写的、只是没人比它更新」。
//   ⇒ `stale` 改成「距今多久没写过」（绝对），`lagging` 保留相对量只当**第二条线索**：
//     stale && lagging = 「游戏还在跑，别的文件已经走到它前面去了」（没跟上）
//     stale && !lagging = 「游戏也停了，它和别人一起停的」（自洽，但仍然是旧的）
const ALIVE_SECONDS = 300;    // 目录 5 分钟内有写入 ⇒ 游戏大概还在跑
const STALE_SECONDS = 600;    // 本文件 10 分钟没写过 ⇒ 页上的数字不是「刚才的情况」
const LAG_SECONDS = 600;      // 别的文件比它新出这么多 ⇒ 它确实没跟上（而不是写得少）

function isoOrNull(ms) {
  return ms == null ? null : new Date(ms).toISOString();
}

function seconds(fromMs, toMs) {
  if (fromMs == null || toMs == null) { return null; }
  return Math.round((toMs - fromMs) / 1000);
}

/** 目录里最新的那个 .jsonl（轮转文件 mtime 更旧，取 max 自然就是活跃文件）。 */
async function newestJsonl(monitorDir) {
  let names;
  try { names = await readdir(monitorDir); }
  catch { return null; }
  let best = null;
  for (const n of names) {
    if (!n.endsWith('.jsonl')) { continue; }
    let s;
    try { s = await stat(join(monitorDir, n)); }
    catch { continue; }
    if (!best || s.mtimeMs > best.ms) { best = { ms: s.mtimeMs, name: n }; }
  }
  return best;
}

function verdictOf(stale, alive, lagging, readable) {
  // ★ 读不到文件时必须先说「读不到」，不许因为「年龄未知」而落到 fresh ——
  //   那正是本工程反复踩的那条：未知被当成 0，再被当成好。
  //   实测：读不到时 stale 会是 false（年龄算不出来），若不先拦，
  //   verdict 会变成「这份数据是新的」—— 观测面在替一个不存在的东西背书。
  if (!readable) { return "unreadable"; }
  if (!stale) { return "fresh"; }
  if (alive) { return "old_while_game_alive"; }
  return lagging ? "old_and_lagging" : "old_but_consistent";
}

const VERDICT_ZH = {
  unreadable: "★ 读不到这个文件 —— 下面的数字全部无效（这不是「0 条」，是观测面坏了）",
  fresh: "这份数据是新的 —— 数字可以当现状看",
  old_while_game_alive: "★ 游戏还在写别的，但这份数据已经很久没更新 —— 数字不是当前这一局的",
  old_and_lagging: "★ 游戏早就停了，而这份数据比其它文件停得更早 —— 它连上一次局都没跟上",
  old_but_consistent: "游戏和这份数据一起停的 —— 数据自洽，但已经是过去的情况（不是当前）",
};

/**
 * @param {object} o
 * @param {string} o.monitorDir
 * @param {string} o.fileName       本页读的那个文件名（例如 "acx.jsonl"）
 * @param {string} [o.dir]          该文件真正所在的目录。默认与 monitorDir 相同。
 *                                   ★ 经验链页有些节点读的是 config/numen/experience-*.jsonl
 *                                     与 learner-memos-*.json —— 它们不在 monitor/ 下，
 *                                     但「游戏最后还在写东西」这个参照系**必须**取 monitor/
 *                                     （那才是引擎写的），否则会拿经验库自己的新鲜度
 *                                     去衡量自己，得出「它很新」的荒唐结论。
 * @param {object|null} o.window    readJsonlWindow 的返回（含 truncated 等窗口事实）
 * @param {string} [o.referenceDir] 「游戏最后活动」的取样目录，默认 monitorDir
 * @param {number} [o.nowMs]
 */
export async function freshnessOf({ monitorDir, fileName, dir, referenceDir, window, nowMs }) {
  const now = nowMs == null ? Date.now() : nowMs;
  const fileDir = dir || monitorDir;
  // 参照系与被测文件可以不是同一个目录（见上面 dir 那段注释）
  const refDir = referenceDir || monitorDir;

  let thisWrite = null;
  try { thisWrite = (await stat(join(fileDir, fileName))).mtimeMs; }
  catch { thisWrite = null; }        // 文件不存在 ⇒ null，不许当 0

  const newest = await newestJsonl(refDir);

  const events = (window && window.events) || null;

  // 这份文件里**读到的**事件覆盖的时间范围。
  // ⚠️ 三者必须分清，不能都退化成 0：
  //   events.length > 0 → 有数据，报真实范围
  //   events.length === 0 → 读到了窗口、窗口里确实是空的（真没有）
  //   events === null → **这次没取窗口事实**（数据由别的读函数提供，
  //                      例如经验链页的 learner.jsonl / experience-*.jsonl）
  const firstAt = events && events.length ? (events[0].timestamp || null) : null;
  const lastAt = events && events.length ? (events[events.length - 1].timestamp || null) : null;

  const sinceNewest = seconds(newest ? newest.ms : null, now);
  const sinceThis = seconds(thisWrite, now);
  const behind = newest && thisWrite != null ? Math.round((newest.ms - thisWrite) / 1000) : null;

  // ★ 三条判据各管一件事，不许互相塌陷：
  //   alive   = 游戏大概还在跑（看的是**目录**最新，不是本文件）
  //   stale   = 本文件是旧的（绝对年龄，页上数字不等于「刚才的情况」）
  //   lagging = 别的文件已经走到它前面去了（相对量，说明它是「没跟上」而不是「写得少」）
  const readable = thisWrite != null;
  const alive = sinceNewest != null && sinceNewest < ALIVE_SECONDS;
  const stale = sinceThis != null && sinceThis > STALE_SECONDS;
  const lagging = behind != null && behind > LAG_SECONDS;
  const verdict = verdictOf(stale, alive, lagging, readable);

  return {
    this_file: fileName,
    // ★ 被测文件与参照系可能不在同一目录（经验链页的节点读 config/numen/ 下的文件），
    //   所以两个路径都要报出来，否则页面上「数据源」与「参照物」对不上号。
    file_dir: fileDir,
    reference_dir: refDir,
    readable,                   // ★ 语义收紧为「这个文件在不在」—— 见下面 file_dir 那段
    // ★ `readable` 的定义只认「文件在不在」（thisWrite != null），
    //   **不**把「这次有没有取窗口事实」混进来。
    //   踩过的坑：经验链页有四个节点的窗口事实是空（它们的数据由 readLearnerView /
    //   readExperienceStoreView 读的，不走 readJsonlWindow），第一版把 `window != null`
    //   也算进 readable ⇒ 那四个节点全被判成 `unreadable`，
    //   而它们的 age/behind 都算得出来 —— **明明读到了却说读不到**，
    //   比不说更坏（读的人会以为那一格坏了）。
    //   「本次没取窗口事实」由 `window === null` 单独表达，不与「读不到」混。
    // ★ null 与 0 必须分开：null = 读不到这个文件（第三种含义），0 = 刚刚写过
    this_file_write: isoOrNull(thisWrite),
    seconds_since_this_file_write: seconds(thisWrite, now),

    // 这份文件里**读到的**事件覆盖的时间范围（可能只是窗口尾部，不是全量）
    first_event_at: firstAt,
    last_event_at: lastAt,
    // ★ null 与 0 分开：null = 这次没量（不是「0 条」），0 = 量了，窗口里确实空的
    events_read: events ? events.length : null,

    // 窗口事实：「读到的」与「文件里有的」差多少
    window: window ? {
      size_bytes: window.sizeBytes,
      window_bytes: window.windowBytes,
      bytes_dropped: window.bytesDropped,
      lines_in_window: window.linesInWindow,
      lines_dropped: window.linesDropped,
      truncated: window.truncated,
      limits: window.limits,
      // ★ 一句能直接放进页面的中文，别让读的人自己算
      note_zh: window.truncated
        ? ("这个文件共 " + window.sizeBytes + " 字节，本次只读了尾部 "
            + window.windowBytes + " 字节 / " + window.events.length + " 条（按 "
            + (window.bytesDropped > 0 ? "字节" : "条数") + "上限截断）——"
            + "所以下面的数字是**最近这一段**的，不是全量")
        : ("整个文件都在读取窗口里（" + window.events.length + " 条，无截断）"),
    } : null,

    // 「游戏最后还在写东西」的时刻（代理，不是会话开始，见文件头边界 1）
    monitor_newest_file: newest ? newest.name : null,
    monitor_newest_write: isoOrNull(newest ? newest.ms : null),
    seconds_since_monitor_newest_write: sinceNewest,

    // ★ 本页第二要紧的一个数：游戏最后活动比这份数据新多少（相对量，只当第二条线索）
    behind_monitor_seconds: behind,
    behind_monitor_note_zh: behind == null
      ? "读不到这份文件的写入时间"
      : (behind <= 0
          ? "这份文件是目录里最新的（所以看不出谁比谁新）"
          : ("别的文件比这份新 " + humanSpan(behind) + " —— 说明它是「没跟上」，而不是「本来就写得少」")),

    alive,                       // 游戏大概还在跑（看目录最新）
    stale,                       // ★ 这份数据是旧的（绝对年龄）
    lagging,                     // ★ 别的文件已走到它前面（相对量）
    verdict,
    verdict_zh: VERDICT_ZH[verdict],
    thresholds: { alive_seconds: ALIVE_SECONDS, stale_seconds: STALE_SECONDS, lag_seconds: LAG_SECONDS },
  };
}

/** 秒 → 人话。刻意不引第三方：观测层越薄越好。 */
export function humanSpan(sec) {
  if (sec == null) { return "?"; }
  const s = Math.abs(Math.round(sec));
  if (s < 90) { return s + " 秒"; }
  const m = Math.round(s / 60);
  if (m < 90) { return m + " 分钟"; }
  const h = Math.round(m / 60);
  if (h < 36) { return h + " 小时"; }
  return Math.round(h / 24) + " 天";
}
