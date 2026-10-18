// ============================================================
// 未知 ≠ 零 —— 第 5 组红线第四条（2026-10-04）
// ============================================================
//
// ★ 这条红线不是在说「页面上要显示 ? 而不是 0」。
//   页面层早就这么做了（`present === false` ⇒ 显示 `?`）。
//   真正的缺口在**数据结构层**：实测 `experience-chain-view.mjs` 里三个节点
//   同时写着 `present: false, count: 0, readable: true` ——
//   **`count: 0` 与「结构上不可观测」被写进了同一个对象**。
//   页面因为读了 `present` 才没被骗过去，但任何只看 `count` 的下游
//   （未来的页、脚本、我自己下一次分析）都会把它读成「0 条」。
//
//   ⇒ 本模块的立场：**「未知」在数据里就不能是一个数字。**
//     未知就给 `null`，并带一个可机读的 `unknown_reason`。
//     数字 0 只在「我确实读了那个地方、那里确实是空的」时候才允许出现。
//
// ── 三态，不是两态 ──────────────────────────────────────────
//
//   known         有数字。0 是合法值，且**就是 0 条**。
//   unknown       我想读那个地方，但读不到（文件不存在 / 解析失败 / 权限）。
//                 ⇒ count = null
//   unobservable  那个观测点**结构上不存在** —— 没有任何一个文件会记录它。
//                 ⇒ count = null，且 reason 说清「没有落盘的地方」
//
// ★ 为什么必须把 unknown 与 unobservable 分开：
//   它们要修的地方不一样。unknown 是「环境/观测面坏了，去修读法或文件」；
//   unobservable 是「设计上就没埋点，要改引擎」（owner 冻结中，改不了）。
//   合成一个「?」之后，读的人只能知道「有个数不对」，不知道该找谁。
//
// ★ 三条不许省的边界：
//
//   1. **`0` 是合法值。** 本模块不负责把 0 变成 ?，也不负责把 ? 变成 0。
//      它只保证「未知的时候那个字段是 null 而不是 0」。
//      把两者搞混的最常见后果：一个真 0 被显示成 ?（少报），
//      或者一个未知被显示成 0（多报）—— 后者更危险，它让人以为「没发生过」。
//
//   2. **窗口没覆盖 ≠ 0。** 「本次读取窗口只覆盖了一部分」是第四种状态
//      （`window_sensitive`），本模块**不合并**它 —— 见 `TRUNCATED`。
//      实测教训：同一批数据用 12MB 窗口与 4MB 窗口算出来的分类会翻转
//      （`eat` 从「两边都有」变成「看不见」）。把它当 0 或当 known 都是错的。
//
//   3. **本模块不读盘、不判定。** 它只提供**词汇与不变量**。
//      谁真的读到了、读没读到，由各 view 自己如实填 ——
//      本模块替它们猜就是在造新的假绿。

/** 四种状态。顺序即「从最确定到最不确定」，页面配色照这个顺序。 */
export const STATE = Object.freeze({
  KNOWN: "known",             // 读到了，数字可信（含 0）
  TRUNCATED: "truncated",     // 读到了，但只覆盖了一部分 ⇒ 数字是下界
  UNKNOWN: "unknown",         // 想读，读不到 ⇒ count 必须是 null
  UNOBSERVABLE: "unobservable", // 结构上没有这个观测点 ⇒ count 必须是 null
});

/** 状态 → 页面该怎么显示这个数字。唯一一份，别处不许另写一套。 */
export const DISPLAY = Object.freeze({
  [STATE.KNOWN]: (v) => (typeof v === "number" ? String(v) : "?"),
  [STATE.TRUNCATED]: (v) => (typeof v === "number" ? `≥${v}` : "?"),
  [STATE.UNKNOWN]: () => "读不到",
  [STATE.UNOBSERVABLE]: () => "无观测点",
});

const REASON_ZH = Object.freeze({
  file_missing: "文件不存在或读不到",
  parse_failed: "文件在，但解析失败",
  not_measured: "本次没有量这一项（数据由别的读函数提供）",
  window_excluded: "这次读取窗口没覆盖到",
  no_sink_exists: "★ 结构上没有落盘的地方 —— 没有任何一个文件会记录它",
  upstream_blind: "★ 上游那一环不可观测，所以这一环也算不出来",
});

/**
 * 造一个「未知 ≠ 零」的值。**这是本模块唯一的入口。**
 *
 * @param {object} o
 * @param {string} [o.state]   四个 STATE 之一。省略时按 count 推断：
 *                             number ⇒ KNOWN，null/undefined ⇒ UNKNOWN。
 *                             ⚠️ 省略推断只在「我确实量过」的地方用；
 *                             「没量过」要显式给 not_measured / no_sink_exists。
 * @param {number|null} [o.count]
 * @param {string} [o.reason]  机器可读的短码，见 REASON_ZH
 * @param {string} [o.note]    一句能直接放进页面的中文
 */
export function measure({ state, count = null, reason, note } = {}) {
  const st = state || (typeof count === "number" ? STATE.KNOWN : STATE.UNKNOWN);
  // ★ 不变量：非 KNOWN 就不许带数字。
  //   这条是本模块存在的全部理由 —— 违反它就等于把「未知」又变回了一个数。
  if (st !== STATE.KNOWN && st !== STATE.TRUNCATED && typeof count === "number") {
    throw new Error(
      `measure(): state=${st} 不许带数字（收到 count=${count}）。`
      + "「未知」必须是 null —— 带数字就变回「0 条」那个老毛病了。");
  }
  if (st === STATE.KNOWN && typeof count !== "number") {
    throw new Error(`measure(): state=known 必须带数字（收到 ${JSON.stringify(count)}）。`);
  }
  return {
    state: st,
    count: st === STATE.KNOWN || st === STATE.TRUNCATED ? count : null,
    display: (DISPLAY[st] || DISPLAY[STATE.UNKNOWN])(count),
    reason: reason || null,
    reason_zh: reason ? (REASON_ZH[reason] || reason) : null,
    note: note || null,
    is_number: st === STATE.KNOWN || st === STATE.TRUNCATED,
  };
}

/** 结构上不存在这个观测点（没有落盘的地方）。最常用的一种「未知」。 */
export const unobservable = (reason = "no_sink_exists", note = null) =>
  measure({ state: STATE.UNOBSERVABLE, reason, note });

/** 想读但读不到。 */
export const unknown = (reason = "file_missing", note = null) =>
  measure({ state: STATE.UNKNOWN, reason, note });

/** 读到了，数字可信（0 是合法值）。 */
export const known = (count, note = null) => measure({ state: STATE.KNOWN, count, note });

/** 读到了，但只覆盖了一部分 ⇒ 数字是下界，显示成 ≥N。 */
export const truncated = (count, reason = "window_excluded", note = null) =>
  measure({ state: STATE.TRUNCATED, count, reason, note });

/**
 * 汇总一列数字，返回「有多少个 known / 有多少个未知」。
 * 页面用它来说清「这一列里有多少格子是真的量过的」——
 * ⚠️ 没有它的话，一列全是 ? 会被读成「全是 0」。
 */
export function auditColumn(values) {
  const out = { total: values.length, known: 0, truncated: 0, unknown: 0, unobservable: 0, zeros: 0 };
  for (const v of values) {
    if (!v) { out.unknown++; continue; }
    if (v.state === STATE.KNOWN) { out.known++; if (v.count === 0) { out.zeros++; } }
    else if (v.state === STATE.TRUNCATED) { out.truncated++; if (v.count === 0) { out.zeros++; } }
    else if (v.state === STATE.UNOBSERVABLE) { out.unobservable++; }
    else { out.unknown++; }
  }
  out.note_zh = out.unknown + out.unobservable > 0
    ? ("这一列有 " + (out.unknown + out.unobservable) + " 格不是量出来的 —— 显示成 ?/读不到/无观测点，"
        + "**不是 0**。其中真 0 有 " + out.zeros + " 个。")
    : ("这一列全部量到了，其中真 0 有 " + out.zeros + " 个。");
  return out;
}