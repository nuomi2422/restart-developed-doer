package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@code learner_note}：干活 AI 遇到问题时写一条原始备忘录。
 *
 * <p>只往队列写一条带环境快照的纸条，<b>不做任何判定、不写经验库</b>。
 * 判定留给 learner_review。
 */
final class LearnerNoteTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** 待办的两种类别（架构 owner 2026-10-03 拍板：同入口但分开处理）。 */
    static final String CAT_LEARNING = "learning";
    static final String CAT_TIMING = "timing";

    /**
     * ★ 常驻（架构 owner 2026-10-03 定：「待办学习队列，执行 AI 要一直都知道这件事」）。
     *
     * <p>改之前这里是默认的 {@code DEFERRED} —— 每轮只在系统提示的
     * {@code <deferred_tools>} 目录里留一行摘要，模型得<b>自己想到去调 {@code find_tools}</b>
     * 才拿得到完整定义。
     *
     * <p><b>为什么必须是常驻</b>：2026-10-03 实测（E2 调查）——
     * 内脑的 {@code find_tools} 在对话里出现 52 次，但它<b>取的是 {@code experience_*} 那几个</b>，
     * <b>一次都没取过 {@code learner_intake}</b>；而 {@code learner_intake} 当时也拿不到。
     * ⇒ 整条「遇事写待办 → 学习者沉淀」的链，<b>从没被走通过</b>。
     * 这不是「AI 不愿调」，是<b>它压根不知道有这回事</b>（或者知道了也没觉得与自己有关）。
     * ⇒ 常驻是让它<b>不可能不知道</b>，而不是指望它自己想起来。
     *
     * <p>⚠️ 代价（所以描述里必须写分寸）：常驻工具的描述<b>每轮都进 prompt</b>，
     * 一句「遇到卡住就写一条」等于<b>每轮都在暗示它写待办</b> ⇒ 可能把噪音当素材投料，
     * 而 {@code MemoQueue.MAX_QUEUE=64} 满了之后按 B12 契约<b>不静默丢</b>（会开始拒收）。
     * ⇒ 对策见 {@link #description()} 里的「不是每件事都要写」与回执里的 {@code queue_slots_left}。
     */
    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.RESIDENT;
    }

    @Override
    public String name() {
        return "learner_note";
    }

    @Override
    public String description() {
        return "Record a raw memo about a problem you hit, for the learner to review later. "
                + "Call this when you are STUCK: a tool failed, you retried repeatedly, an action had "
                + "no effect, or you had to abandon a sub-task. "
                // ★ 2026-10-03：分寸约束。这一段是「改常驻」的必要配套 ——
                //   常驻意味着每轮都看得见这句描述，如果不写清楚分寸，
                //   「遇事就写一条」会变成默认动作，学习者复盘时收到一堆低价值待办。
                //   与 59 号 §4.2「经验库最怕的就是什么都能记」是同一条原则，
                //   只是这里对**投料侧**说（那边是对产出侧说）。
                + "WHEN NOT TO WRITE: do not write a memo for routine steps, for things that just "
                + "worked out, or for anything you did not actually get stuck on. A day with no memo "
                + "is normal; a day with thirty is a bug in your judgement, not a sign of diligence. "
                + "Also do NOT write a memo for something you already solved in the same turn - "
                + "just say what you did. "
                // ★ 类别：同入口但分开处理（owner 拍板）。时序类最终走 USE_AC 产物。
                + "category: use \"timing\" for ordering/sequencing problems (this task needs X first, "
                + "wait for Y before Z, this step must happen before that one) - those usually become "
                + "an AC script. Use \"learning\" (the default) for anything else. "
                + "Required: problem, tried. "
                + "IMPORTANT - snapshot: when you are stuck you MUST also hand over the environment "
                + "you were stuck in, otherwise the learner cannot tell a real 'no extra gear needed' "
                + "from 'we had no idea what the world looked like'. Write it as key=value pairs "
                + "separated by commas, e.g. "
                + "hp=6/20, armor=none, weapon=none, nearby=zombie, dim=overworld. "
                + "Keys it understands: hp (or health), armor/chestplate/helmet/leggings/boots, "
                + "weapon/sword/axe/bow, nearby (entity names), dim, hostile (true/false). "
                + "If you truly cannot read the environment, pass snapshot=\"unavailable\" and the "
                + "reply will tell you the review was degraded. "
                + "This only appends to a queue; it does NOT fix anything by itself. "
                // ★ 回执里有这个：让 AI 自己能判断该不该再写（而不是写满了才知道）
                + "The reply tells you how many slots are left in the queue - stop writing when it "
                + "runs low, and prefer writing the one memo that mattered most over three that did not.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("problem", "What went wrong or blocked you (concrete, one or two sentences).")
                .string("tried", "What you already tried before giving up on this.")
                .optionalString("stage", "Which stage/phase this happened in.")
                // ★ 2026-10-03 新增（owner 拍板「同入口但分开处理」）。
                //   不做成必填：常驻工具每轮都占 prompt，一个必填参数会诱使它每次都得填；
                //   缺省 learning，走 62 号 §2 的 source=experience 那一侧。
                .optionalString("category", "learning (default) or timing. "
                        + "Use \"timing\" for ordering/sequencing problems (needs X first, wait for Y "
                        + "before Z) - those usually become an AC script. Use \"learning\" for anything else.")
                .optionalString("snapshot", "REQUIRED WHEN STUCK. Environment as key=value pairs separated by commas, "
                        + "e.g. hp=6/20, armor=none, weapon=none, nearby=zombie, dim=overworld. "
                        + "Keys: hp|health, armor|chestplate|helmet|leggings|boots, weapon|sword|axe|bow, "
                        + "nearby (entity names), dim, hostile (true|false). "
                        + "Pass \"unavailable\" only if you genuinely cannot read it - the reply will say so.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        // 读世界状态必须在服务端主线程（框架用 dispatchAsync 触发，不保证在主线程）。
        // 解析入参不碰世界，可以先做；采集与入队整体放进主线程。
        Input in;
        try {
            in = GSON.fromJson(args, Input.class);
        } catch (RuntimeException e) {
            reply.accept(TaskResult.fail("learner_note bad arguments: " + e.getMessage()).toJson());
            return;
        }
        if (in == null || blank(in.problem()) || blank(in.tried())) {
            reply.accept(TaskResult.fail("learner_note requires problem and tried").toJson());
            return;
        }

        EnvSnapshot.onServerThread(companion, () -> {
            try {
                UUID id = companion.getUUID();
                MemoQueue q = LearnerPlugin.queue(id);
                String memoId = "m-" + System.currentTimeMillis() + "-" + Math.abs(in.problem().hashCode() % 1000);

                // 合并策略（38号v3 §2.2 实现约束 4）：**调用方传的优先**，为空才自动采集。
                // 「固化环境」是系统的义务，但系统不能覆盖调用方已经知道并写下来的真值。
                String snapshot = nz(in.snapshot()).trim();
                boolean autoCollected = false;
                if (snapshot.isEmpty() && companion != null) {
                    snapshot = EnvSnapshot.capture(companion).trim();
                    autoCollected = !snapshot.isEmpty();
                }

                Memo memo = new Memo(memoId, in.problem().trim(), nz(in.stage()), in.tried().trim(),
                        snapshot, System.currentTimeMillis(), nz(in.category()));

                if (!q.append(memo)) {
                    // 分不清是「满了」还是「字段太长」时不猜：两种都如实报，并给出当前深度
                    int depthNow;
                    try {
                        depthNow = q.size();
                    } catch (RuntimeException e) {
                        depthNow = -1;
                    }
                    reply.accept(TaskResult.fail("memo not queued: queue full (" + MemoQueue.MAX_QUEUE
                            + ") or field too long (" + MemoQueue.MAX_FIELD_CHARS
                            + " chars); run learner_review to drain first; current depth=" + depthNow).toJson());
                    return;
                }

                int depth = q.size();

                // B21（缺失的表达方式）：缺失就是缺失，不许用哨兵值伪装成数据。
                // 契约与实现都下沉到 core 的 Memo.carrierSignal()，那里是纯 JVM，单测跑得到。
                boolean snapshotPresent = memo.hasSnapshot();
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("memo_id", memoId);
                ev.put("companion", id.toString());
                ev.put("queue_depth", depth);
                ev.put("category", memo.category());
                ev.put("queue_slots_left", Math.max(0, MemoQueue.MAX_QUEUE - depth));
                ev.put("snapshot_source", snapshotPresent ? (autoCollected ? "auto" : "caller") : "none");
                ev.putAll(memo.carrierSignal());
                LearnerMonitor.publish("noted", ev);

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("memo_id", memoId);
                data.put("queue_depth", depth);
                data.put("queue_slots_left", Math.max(0, MemoQueue.MAX_QUEUE - depth));
                data.put("category", memo.category());
                data.put("snapshot_present", snapshotPresent);
                data.put("snapshot_source", snapshotPresent ? (autoCollected ? "auto" : "caller") : "none");
                if (snapshotPresent) {
                    data.put("carrier_preview", memo.carrierPreview());
                    data.put("carry_list", memo.carrierSignal().get("carry_list"));
                } else {
                    // 不回传内部措辞（"无法分级"），改成对调用方可执行的提示
                    data.put("carry_list", java.util.List.of());
                    data.put("carry_list_meaning", "UNKNOWN_NO_SNAPSHOT");
                    data.put("review_degraded", true);
                    data.put("snapshot_hint",
                            "the learner could not read the environment either, so this memo cannot be graded. "
                                    + "The companion may be unloaded or the world still loading.");
                }
                reply.accept(TaskResult.ok("memo queued for learner review", data).toJson());
            } catch (RuntimeException ex) {
                // 队列读失败会抛 IllegalStateException（刻意不按空队列覆盖，见 MemoQueue.readAll）
                reply.accept(TaskResult.fail("learner_note failed: " + ex.getMessage()).toJson());
            }
        });
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * ★ {@code category} 刻意做成<b>可选</b>（缺省 {@code learning}），不做成必填：
     * 这个工具刚变成常驻（每轮都进 prompt），必填参数会诱使它每轮都填一次 ——
     * 而「分类」这件事只在真的要写待办时才需要想。
     *
     * <p>而且 {@link Memo} 的规范构造器会把 null/空白也归成 {@code learning}，
     * 所以「没传」与「传了 learning」不需要在下游分两种情形处理。</p>
     */
    private record Input(String problem, String tried, String stage, String snapshot, String category) {}
}
