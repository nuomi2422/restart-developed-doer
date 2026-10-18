package com.dwinovo.numen.client.command;

import com.dwinovo.numen.client.agent.EntityAgentLoop;

import java.util.List;
import java.util.Locale;

/**
 * {@code /stop} —— 让她彻底停下（连同她的任务链和排队的话）。
 *
 * <h2>为什么需要这条命令（2026-10-04 实机 bug）</h2>
 * 主人发现的不是「停不下来」，是<b>「清了目标还在烧钱」</b>：{@code /goal clear} 把 RDD 的
 * 任务链清干净了，可内建 agent 的<b>身体感知层不受目标约束</b>——同伴在水里反复溺水，
 * {@code body_log} 事件照旧一条条进队列；队列一过阈值就自动开一轮，一开轮就是一次真实
 * LLM 请求。实测 {@code ai.jsonl} 45 秒涨 2501 字节，钱照烧，而主人手里没有任何一个
 * 动作能让她停下（{@code /goal} 只管目标，管不着她自己在干什么）。
 *
 * <h2>它和 {@code /goal clear} 的分工</h2>
 * <b>目标</b>（要她做什么）是 {@code /goal} 管的；<b>停不停</b>是这条管的。两者都要做才
 * 彻底：{@code /goal clear} 只清目标，{@code /stop} 连目标带身体一起停。
 *
 * <h2>为什么停下之后还能恢复</h2>
 * 闩是 {@code AgentTurnPause.OWNER_INTERRUPT}——主人喊停的优先级最高，<b>不会被任何事件
 * 唤醒悄悄撤销</b>（否则「停下」会被一次 body_log 又叫起来，等于没停）。要恢复必须
 * 主人自己说一声：{@code /stop resume}，或者直接跟她说话（说句话本身就会解除闩）。
 *
 * <h2>如实回报</h2>
 * 不回一句笼统的「好了」：停的那一刻她在不在跑、倒掉多少条排队的话、其中多少条早就
 * 溢出丢过了——都报出来。主人要判断的是「刚才那段时间烧了多少」，含糊的回复没法判断。
 */
final class StopCommand implements ChatCommand {

    /** 「恢复」的各种说法。主人想让她重新动，脑子里冒出哪个词都算。 */
    private static final java.util.Set<String> RESUME_WORDS =
            java.util.Set.of("resume", "start", "on", "go", "continue", "unstop");

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "让她彻底停下（清目标+停身体）；/stop resume 恢复";
    }

    @Override
    public String argHint() {
        return "[resume]";
    }

    @Override
    public boolean touchesContext() {
        return true;
    }

    @Override
    public List<Completion> completeArgs(EntityAgentLoop loop, String partial) {
        String p = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        if (p.startsWith("res")) {
            return List.of(new Completion("resume", "/stop resume", "解除主人喊停的闩，让她可以自己行动", true, true));
        }
        return List.of();
    }

    @Override
    public String run(EntityAgentLoop loop, String args) {
        String a = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        if (RESUME_WORDS.contains(a)) {
            return loop.ownerResume()
                    ? "解除了。她可以自己行动了。"
                    : "她本来就没被你喊停。";
        }
        if (!a.isEmpty()) {
            // 不静默吞掉：认不出来就照直说,别把打错的话变成一句「停了」。
            return "不认得「" + args.trim() + "」。要停就 /stop,要恢复就 /stop resume。";
        }
        EntityAgentLoop.StopReport r = loop.ownerStop();
        StringBuilder sb = new StringBuilder("停了。");
        sb.append(r.wasBusy() ? "她这一轮正在跑,已打断。" : "她本来就没在跑。");
        if (r.queuedDropped() > 0) {
            sb.append("倒掉排队的话 ").append(r.queuedDropped()).append(" 条。");
        }
        if (r.overflowDropped() > 0) {
            // 溢出丢的那批是早就扔掉的,主人在面板上根本看不到——不说就等于账没结。
            sb.append("另外早先已经溢出丢掉 ").append(r.overflowDropped()).append(" 条。");
        }
        sb.append("\n要她重新动:/stop resume（或直接跟她说句话）。");
        return sb.toString();
    }
}