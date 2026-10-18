import { readdir, readFile } from "node:fs/promises";
import { join, resolve } from "node:path";

/**
 * 经验库「按内容」视图 —— 回答「经验库到底装了什么」，而不只是「有多少条」。
 *
 * <p>★ 为什么要单独一页（2026-10-03 owner 反馈「经验库很乱、难以识别」）：
 * 原来的断点页只给<b>条数</b>，于是 66 条看着像「知识很多」。
 * 实际上 <b>只有 24 个不同标题，64% 是重复的</b> —— 三个同伴各存了一份同样的种子数据。
 * <b>「条数」与「知识量」在这套库里不是一回事</b>，只给条数就等于在骗人。</p>
 *
 * <p><b>★ 三条设计红线</b>（写在最前面，因为它们决定了这页能不能用）：</p>
 * <ol>
 *   <li><b>重复必须显式报出来</b>，不许只给「66 条」。同一条经验存进几个同伴的库，
 *       是不是「真的学到了三份」，还是「种子数据复制了三份」，读的人<b>分不出来</b> ——
 *       而这两种要区别对待：前者是好事，后者是账面虚胖。</li>
 *   <li><b>文件形状冲突必须显式报出来</b>：「一行一条」与「整文件数组」混在一起时，
 *       后者<b>整个被跳过</b>（程序读到非对象行会抛异常，被 catch 吞掉）。
 *       所以「21 条在那儿，但程序读到 0 条」是可能的，<b>只报条数会掩盖它</b>。</li>
 *   <li><b>每条都要能展开看七项原文</b>：计数证明不了「内容对不对」，
 *       而 owner 要的就是「看内容那些、确认一下」。</li>
 * </ol>
 */

const SEVEN = ["mechanism", "preconditions", "failure_conditions", "observable_signal",
  "derivation", "efficiency", "evidence"];

/** 七个槽位的中文名（页面上给 owner 看，不给他字段名）。 */
const SEVEN_ZH = {
  mechanism: "背后规律",
  preconditions: "什么条件下能用",
  failure_conditions: "什么情况下会害你",
  observable_signal: "看得见的判据",
  derivation: "从判据到动作",
  efficiency: "比笨办法省多少",
  evidence: "证据出处",
};

/**
 * 读一个经验文件。
 *
 * @returns {{file:string, shape:"jsonl"|"json-array"|"unknown", entries:Array, readableAsJsonl:boolean, bytes:number, error?:string}}
 */
async function readExperienceFile(path, name) {
  const bytes = (await import("node:fs/promises")).stat ? undefined : undefined;
  let raw = "";
  try {
    raw = await readFile(path, "utf8");
  } catch (e) {
    return { file: name, shape: "unknown", entries: [], readableAsJsonl: false, bytes: 0, error: String(e.message) };
  }
  const text = raw.trim();
  let shape = "unknown";
  let entries = [];
  let error;
  if (text.startsWith("[")) {
    shape = "json-array";
    try {
      const arr = JSON.parse(text);
      if (Array.isArray(arr)) entries = arr.filter((e) => e && typeof e === "object");
    } catch (e) {
      error = "parse failed: " + String(e.message);
    }
  } else if (text.length > 0) {
    shape = "jsonl";
    for (const line of text.split(/\r?\n/)) {
      if (!line.trim()) continue;
      try {
        const o = JSON.parse(line);
        if (o && typeof o === "object") entries.push(o);
      } catch (e) {
        // 一行坏了就整条链读不出来（原实现的真 bug），所以这里必须如实计数
        error = (error ? error + "; " : "") + "bad line";
      }
    }
  }
  return {
    file: name,
    shape,
    entries,
    // ★ 「整文件数组」这个形状，程序逐行读时会抛 IllegalStateException 并被 catch 吞掉
    //   ⇒ 这些条目<b>代码读不进去</b>。readableAsJsonl 就是这个事实的读数。
    readableAsJsonl: shape === "jsonl",
    bytes: raw.length,
    error,
  };
}

/**
 * 一条经验的展示形状。
 *
 * @param e 原始 JSON 对象
 * @param companions 这条经验出现在几个同伴的库里（同一个标题算同一份）
 */
function toEntry(e, companions, primaryFile) {
  const seven = {};
  let filled = 0;
  for (const k of SEVEN) {
    const v = e[k];
    const has = typeof v === "string" && v.trim().length > 0;
    seven[k] = { zh: SEVEN_ZH[k], value: has ? v : "", filled: has };
    if (has) filled++;
  }
  return {
    id: e.id || "",
    type: e.type || "",
    title: e.title || "(无标题)",
    maturity: e.maturity || "",
    verifiedCount: typeof e.verified_count === "number" ? e.verified_count : null,
    priority: typeof e.priority === "number" ? e.priority : null,
    retracted: e.retracted === true,
    createdAt: e.created_at || 0,
    verifiedAt: e.verified_at || 0,
    seven,
    sevenFilled: filled,
    sevenComplete: filled === SEVEN.length,
    companions,
    primaryFile,
  };
}

/**
 * 建这一页的读数。
 *
 * @param configDir live 实例的 {@code config/numen} 目录
 */
export function createExperienceLibraryView(configDir) {
  return {
    async read() {
      let names = [];
      try {
        // ★ 只收**正式经验库**：`experience-<uuid>.jsonl`。
        //   我第一版只判 startsWith("experience-")，结果把
        //   `experience-xxx.jsonl.pre-append-*.bak`（原子重写留下的备份）
        //   与 `experience-llm-seed.json`（种子，不是 Store 会读的东西）
        //   **一起算进了账面条数** ⇒ 83 条里有 59 条是程序根本读不到的。
        //   —— 只报条数而不区分「正式库」与「备份/种子」，就是在骗人。
        names = (await readdir(configDir))
          .filter((n) => /^experience-[0-9a-fA-F-]{36}\.jsonl$/.test(n))
          .sort();
      } catch (e) {
        return {
          ok: false,
          error: "读不到配置目录: " + String(e.message),
          files: [], byTitle: [], totals: null,
          timestamp: new Date().toISOString(),
        };
      }

      const files = [];
      for (const n of names) {
        const f = await readExperienceFile(join(configDir, n), n);
        files.push(f);
      }

      // ★ 按标题聚合：同一条经验存进几个同伴的库，在这里会被认出来。
      //   这是「66 条其实只有 24 份」这个事实唯一能被看见的地方。
      const byTitleMap = new Map();
      for (const f of files) {
        for (const e of f.entries) {
          const key = (e.title || "(无标题)").trim();
          let agg = byTitleMap.get(key);
          if (!agg) {
            agg = { title: key, files: [], types: new Set(), entry: null };
            byTitleMap.set(key, agg);
          }
          agg.files.push(f.file);
          if (e.type) agg.types.add(e.type);
          // 内容用第一个可读的做展示；读到的是哪一份会单独报出来
          if (!agg.entry && f.readableAsJsonl) agg.entry = e;
        }
      }

      const byTitle = [...byTitleMap.values()]
        .map((a) => ({
          ...toEntry(a.entry || {}, a.files.length, a.files[0]),
          // 同一个标题在不同文件里，类型可能不一致 —— 那本身是件要让人看见的事
          typesSeen: [...a.types],
          typeConflict: a.types.size > 1,
          files: a.files,
        }))
        .sort((x, y) => x.title.localeCompare(y.title, "zh"));

      const totalEntries = files.reduce((a, f) => a + f.entries.length, 0);
      const distinctTitles = byTitle.length;
      const unreadableFiles = files.filter((f) => f.entries.length > 0 && !f.readableAsJsonl);

      return {
        ok: true,
        configDir,
        timestamp: new Date().toISOString(),
        files: files.map((f) => ({
          file: f.file,
          shape: f.shape,
          entries: f.entries.length,
          readableAsJsonl: f.readableAsJsonl,
          bytes: f.bytes,
          error: f.error,
          // ★ 这条形状的文件，程序逐行读会整个跳过 ⇒ 「21 条在那儿但读到 0 条」
          readAsZero: !f.readableAsJsonl && f.entries.length > 0,
          shapeZh: f.shape === "jsonl" ? "一行一条（程序能读）"
            : f.shape === "json-array" ? "整文件一个数组（程序读不进去）" : "认不出",
        })),
        byTitle,
        totals: {
          totalEntries,
          distinctTitles,
          // ★ 「条数」与「知识量」不是一回事。只报前者就是在骗人。
          duplicateRatio: totalEntries > 0
            ? Math.round(100 - (distinctTitles * 100) / totalEntries)
            : 0,
          duplicateCopies: Math.max(0, totalEntries - distinctTitles),
          unreadableFiles: unreadableFiles.length,
          unreadableEntries: unreadableFiles.reduce((a, f) => a + f.entries.length, 0),
          sevenComplete: byTitle.filter((e) => e.sevenComplete).length,
          typeConflicts: byTitle.filter((e) => e.typeConflict).length,
          retracted: byTitle.filter((e) => e.retracted).length,
          verifiedAtLeastOnce: byTitle.filter((e) => e.verifiedAt > 0).length,
        },
        // ★ 三句给 owner 看的大白话结论，不藏在数据里
        plain: [
          totalEntries > 0 && distinctTitles < totalEntries
            ? "账面 " + totalEntries + " 条，实际只有 " + distinctTitles
              + " 份不同内容（" + (100 - Math.round((distinctTitles * 100) / totalEntries))
              + "% 是重复的）—— 同一条经验被存进了多个同伴的库。"
            : "账面 " + totalEntries + " 条，都是不同内容。",
          unreadableFiles.length > 0
            ? "有 " + unreadableFiles.length + " 个文件是「整文件一个数组」的形状，程序读不进去（"
              + unreadableFiles.reduce((a, f) => a + f.entries.length, 0)
              + " 条在那儿但代码读到 0 条）。"
            : "文件形状都对，程序都读得进去。",
          byTitle.filter((e) => e.sevenComplete).length === 0 && byTitle.length > 0
            ? "没有一条七项都填齐的 —— 全是早期种子数据的样子。"
            : byTitle.length + " 份内容里，" + byTitle.filter((e) => e.sevenComplete).length + " 份七项填齐。",
        ],
      };
    },
  };
}
