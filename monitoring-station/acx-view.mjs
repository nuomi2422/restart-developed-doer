// ACX（AC 执行层）状态机可见面 —— 把 acx.jsonl 的事件流还原成「一条条运行的状态机」。
// 为什么要单独一页：AC 的运行状态（跑到第几步、哪一步暂停、为什么、循环几轮、变量是什么）
// 全在事件流里，不还原就只能看一堆 JSON 行 —— 那是「不可视」而不是「可视化」。
//
// ★ 两条不许省的读数（这一份文件同时记着两个「同一个病」的实例）：
//
//   〔1〕「STEP_FAILED」不等于「失败」（TODO AC-B20，2026-10-04）
//   引擎的 `AcxRunner:567-572` 对任何非成功结局都发 `STEP_FAILED`，**PAUSED 也在内**
//   —— 也就是「动作已受理、还在世界里执行」这件事被记成了失败。事件里明明带着
//   `data.status`，所以**读数面必须看 status，不能只看 kind**（这一版最初只看 kind，
//   于是监测台把「她正在走过去」标成红色失败）。
//   实机数字（live acx.jsonl，2539 行）：STEP_FAILED|PAUSED=53 / STEP_FAILED|FAIL=29 /
//   STEP_PAUSED|PAUSED=1 ⇒ 只按 kind 统计的话，**假失败比真失败还多 1.8 倍**。
//   ⚠️ 修引擎那一侧属功能改动（owner 冻结中），所以这里做的是「不许把两种混成一种」。
//
//   〔2〕页上的数字是**什么时候**的（第 5 组红线「历史 ≠ 当前」）
//   实测反例（2026-10-04 07:20）：本页显示「65 次运行」，而 acx.jsonl 停在
//   10-03 17:32，monitor 目录里其它文件停在 10-04 01:56 —— **差 8.4 小时**。
//   也就是说那 65 次是**上一局**的，而页面从头到尾没说过这件事。
//   ⇒ 本页所有数字都必须先说清「覆盖哪段时间」「停更多久」「是不是当前这一局」。
import { join } from "node:path";
import { readJsonlWindow } from "./tail-jsonl.mjs";
import { freshnessOf } from "./freshness.mjs";

const MAX_FILE_BYTES = 8 * 1024 * 1024;
const MAX_EVENTS = 4000;

/** 「在飞」= 步骤已受理但还没做完。此刻身体动作仍在进行，不是失败。 */
const STATUS_WAITING = "waiting";

/**
 * 事件 kind + 事件自带 status → 该步骤的显示状态。
 *
 * ★ `status` 不是可选的装饰：没有它，PAUSED 的 STEP_FAILED 会被读成 failed。
 *   实机 53 : 29 —— 这是本页最要紧的一条读数纪律。
 */
function stepStatus(kind, status) {
  const st = String(status || "").toUpperCase();
  if (kind === "STEP_FAILED" || kind === "STEP_PAUSED") {
    // 受理即暂停 = 在飞；真失败只有 status=FAIL/FAILED 这一路
    return st === "PAUSED" ? STATUS_WAITING : "failed";
  }
  switch (kind) {
    case "STEP_STARTED": return "running";
    case "STEP_SUCCEEDED": return "ok";
    case "STEP_IGNORED_FAILURE": return "ignored";
    case "CONTROL_STAGNATED": return "stagnated";
    case "CONTROL_MAX_ITERS": return "maxiters";
    case "GUARD_FAILED": return "guard";
    case "IF_UNMATCHED": return "ifmiss";
    case "REF_UNRESOLVED": return "refbad";
    case "VERIFY_FAILED": return "verifybad";
    case "CIRCUIT_STEPS": return "circuit";
    case "CIRCUIT_TIMEOUT": return "circuit";
    case "CIRCUIT_RECURSION": return "circuit";
    default: return null;
  }
}

/**
 * 从事件 data 里取「为什么」。
 *
 * ★★ 这里原来写的是 `d.detail.reason`，**永远是空的** —— 引擎侧
 *   `AcxEvent.toMap()` 最后一行的 `m.putAll(detail)` 是**平铺**进 data 的，
 *   真实形状是 `data.reason`，不存在 `data.detail` 这一层。
 *   后果：监测台每一步的「为什么」栏从来没显示过任何字。
 *   （同一个文件、同一种病：字段明明在数据里，消费者去错的地方找 —— 与
 *     `ai.jsonl` 不记工具名、`tools.jsonl` 不记 find_tools 是同一族。）
 *   两种形状都读：平铺是引擎的真源，嵌套只当防御，别把防御写成真源。
 */
function reasonOf(d) {
  if (!d) { return ""; }
  const flat = d.reason || d.note || d.why;
  if (flat) { return String(flat); }
  const nested = d.detail;
  if (nested && typeof nested === "object") {
    return String(nested.reason || nested.note || nested.why || "");
  }
  return "";
}

/**
 * 从 reason 文本里抠出在飞的任务 id。
 *
 * ⚠️ 这是**降级读法**，不是正常通道：引擎把 `task_id` 作为字段丢掉了
 * （`AcxRunner:567-572` 的 detail 只有 reason + elapsed_ms），
 * 只在 `PortToolAdapter.acceptMessage` 生成的文本 "...后台执行中: taskId=t1" 里留着。
 * 引擎修好之后这个函数应当被字段取代；在那之前它至少让读的人看见「她在动哪个任务」。
 */
function inFlightTaskId(reason) {
  const m = String(reason || "").match(/taskId=([^\s,;"']+)/);
  return m ? m[1] : "";
}

export function createAcxView(MONITOR_DIR) {
  const FILE = "acx.jsonl";

  /** 读不出文件时返回 null 而不是空数组 —— 「读不到」与「真的是空的」不是一回事。 */
  async function readWindow() {
    try {
      return await readJsonlWindow(join(MONITOR_DIR, FILE), MAX_FILE_BYTES, MAX_EVENTS);
    } catch {
      return null;
    }
  }

  /** 把事件流按 run_id 归并成运行态。同一 step 保留最后一条状态，但违规信息聚合成 notes。 */
  function fold(events) {
    const runs = new Map();
    for (const e of events) {
      const d = e.data || {};
      const runId = d.run_id;
      if (!runId) continue;
      let run = runs.get(runId);
      if (!run) {
        run = {
          run_id: runId,
          ac_name: d.ac_name || "?",
          status: "RUNNING",
          firstAt: e.timestamp,
          lastAt: e.timestamp,
          steps: [],
          notes: [],
          terminal: null,
        };
        runs.set(runId, run);
      }
      run.lastAt = e.timestamp;
      const kind = d.kind || e.type || "?";
      const stepId = d.step_id;

      if (kind === "RUN_FINISHED") {
        run.status = d.status || (d.detail && d.detail.status) || run.status;        run.terminal = { status: run.status, elapsed_ms: d.elapsed_ms, step_index: d.completed_step_index };
        continue;
      }
      if (kind === "RUN_STARTED" || kind === "RESUME_STARTED" || kind === "RESUME_REJECTED" || kind === "RUN_STARTED") {
        if (kind === "RESUME_REJECTED") {
          run.status = "REJECTED";
          run.notes.push({ at: e.timestamp, kind, why: reasonOf(d) });
        }
        continue;
      }
      if (!stepId) continue;

      let step = run.steps.find(s => s.id === stepId);
      if (!step) {
        step = { id: stepId, block: null, status: "pending", at: e.timestamp, notes: [] };
        run.steps.push(step);
      }
      const st = stepStatus(kind, d.status);
      if (st === "running") {
        step.status = "running";
      } else if (st) {
        // ok / paused / failed / waiting 是终态，其它（违规类）只记 note 不覆盖状态
        if (st === "ok" || st === "paused" || st === "failed" || st === STATUS_WAITING) {
          step.status = st;
        } else {
          step.status = step.status === "pending" || step.status === "running" ? st : step.status;
          step.notes.push({ kind, detail: reasonOf(d) });
        }
        // ⚠️ 这里原来写的是 `if (d.detail && ...)` —— 引擎把 detail **平铺**进 data，
        //   `d.detail` 恒为 undefined ⇒ 整个「为什么 / 在飞任务」块从来没执行过。
        //   判据改成「取得到 reason」，这才是「这条事件带没带说明」的真判据。
        if (st === "failed" || st === "paused" || st === "guard"
            || st === "stagnated" || st === STATUS_WAITING) {
          step.why = reasonOf(d);
          // ★ 在飞的任务 id：先读字段（引擎修好后就有），读不到才从文本里抠
          const tid = d.task_id != null && d.task_id !== ""
              ? String(d.task_id) : inFlightTaskId(step.why);
          if (tid) { step.in_flight_task = tid; }
        }
      }
      if (d.loop_count != null) run.loop_count = d.loop_count;
      if (d.elapsed_ms != null) run.elapsed_ms = d.elapsed_ms;
    }
    return [...runs.values()].sort((a, b) => (a.lastAt < b.lastAt ? 1 : -1));
  }

  return {
    async all() {
      const win = await readWindow();
      // ★ 读不到文件时必须能说「读不到」，不能退化成「0 条」
      const events = win ? win.events : [];
      const runs = fold(events);
      const totals = { runs: runs.length, success: 0, paused: 0, failed: 0, timeout: 0, running: 0, rejected: 0 };
      for (const r of runs) {
        if (r.status === "SUCCESS") totals.success++;
        else if (r.status === "PAUSED") totals.paused++;
        else if (r.status === "FAIL" || r.status === "FAILED") totals.failed++;
        else if (r.status === "TIMEOUT") totals.timeout++;
        else if (r.status === "REJECTED") totals.rejected++;
        else totals.running++;
      }
      const kinds = {};
      // ★ 按 kind × status 分开数：只看 kind 会把「在飞」算成「失败」（见文件头）
      const kindsSplit = {};
      let stepFailedPaused = 0;
      let stepFailedFail = 0;
      for (const e of events) {
        const k = (e.data && e.data.kind) || e.type || "?";
        kinds[k] = (kinds[k] || 0) + 1;
        const s = (e.data && e.data.status) || "-";
        const key = k + "|" + s;
        kindsSplit[key] = (kindsSplit[key] || 0) + 1;
        if (k === "STEP_FAILED" && String(s).toUpperCase() === "PAUSED") stepFailedPaused++;
        if (k === "STEP_FAILED" && (String(s).toUpperCase() === "FAIL" || String(s).toUpperCase() === "FAILED")) stepFailedFail++;
      }
      const stepWaitingTotal = runs.reduce(
          (n, r) => n + r.steps.filter(s => s.status === STATUS_WAITING).length, 0);
      return {
        ok: true,
        source: "acx-jsonl",
        file: join(MONITOR_DIR, "acx.jsonl"),
        // ★ 读不到文件时 ok 仍然是 true，但 freshness.readable=false 且 window=null
        //   —— 让页面能说「读不到」，而不是显示一片 0
        freshness: await freshnessOf({ monitorDir: MONITOR_DIR, fileName: FILE, window: win }),
        totals,
        kinds,
        kindsSplit,
        // 供页面顶部说清「失败」这个数字到底含不含「还在动」
        caveat: {
          id: "step-failed-includes-in-flight",
          steps_logged_as_failed: kinds.STEP_FAILED || 0,
          of_which_really_failed: stepFailedFail,
          of_which_still_in_flight: stepFailedPaused,
          runs_waiting_steps: stepWaitingTotal,
          note: "引擎把「已受理、还在执行」也记成 STEP_FAILED（TODO AC-B20，AcxRunner:567-572）。"
              + "本页已按事件自带的 status 把两者分开；引擎修好之前，"
              + "「失败」这个数只等于 really_failed，不等于 steps_logged_as_failed。",
        },
        runs,
      };
    },
  };
}
