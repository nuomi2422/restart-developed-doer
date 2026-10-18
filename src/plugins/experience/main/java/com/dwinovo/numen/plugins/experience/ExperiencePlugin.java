package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.experience.core.ExperienceDirectory;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.LexicalExperienceRetriever;
import com.dwinovo.numen.experience.core.PresentationReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NUMEN 宿主适配器：注册经验工具，并把每只同伴的经验库挂进运行时状态。
 *
 * <p>经验按主人/同伴隔离，落盘 {@code config/numen/experience-<uuid>.jsonl}。
 * 第一版检索用纯词法（零外部依赖）；将来接 ChatCore/Numen 已有向量记忆时，
 * 把 {@link #memory} 的 retriever 换成向量实现即可，模型与工具不动。
 */
public final class ExperiencePlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(ExperiencePlugin.class);

    private static final Map<UUID, ExperienceMemory> MEMORIES = new ConcurrentHashMap<>();
    private static volatile Path configDir;

    @Override
    public void setup(NumenApi numen) {
        configDir = numen.configDir();
        numen.registerTool(new ExperienceLearnTool());
        numen.registerTool(new ExperienceRecallTool());
        numen.registerTool(new ExperienceVerifyTool());
        numen.registerTool(new ExperienceRetractTool());
        // 规划知识：让任务链规划器(Stage-A/Stage-B/回退)真正拿到 guide + builtin + 该同伴经验。
        // 规划器与本插件互不可见，只能通过这扇宿主门通信；知识只是参考资料，不改任务结构。
        numen.contributePlanningKnowledge(query -> {
            try {
                PlanningKnowledge.Request req = PlanningKnowledge.Request.of(
                        query.objective(), query.stage(), query.knownFailures());
                return ExperienceKnowledgeSource.recall(query.companion(), req).text();
            } catch (Throwable t) {
                // 知识是可选增强：坏了就这段留空，规划照常（宿主侧还有一层隔离兜底）
                LOG.warn("[expmem] planning knowledge unavailable: {}", t.toString());
                return "";
            }
        });
        numen.contributeState(companion -> {
            ExperienceMemory m = MEMORIES.get(companion);
            if (m == null) {
                return "";
            }
            try {
                // ★ E6：不再是「只有三个计数」，而是 L0 目录层（59 号 §8.1）。
                // contributeState 只拿得到同伴 UUID —— 拿不到当前任务，所以这里排的是
                // 「可信度兜底序」（经验库 recall("", …) 的 fallbackScore 路径：
                // 成熟度 × 优先级 × 近期性），**不是**按当前任务筛的；
                // 块里的 <note> 明说了这一点，别让 AI 把顺序读成相关性。
                // ★ E7：reportable=false —— 目录是每轮自动印的，同伴很可能压根没看；
                //   让它进待回报清单会产出满屏「本任务没有结论」的噪音。
                //   它仍然计入「被呈现过几次」那个长期读数。
                ExperienceDirectory.Block block = ExperienceDirectory.render(
                        m.recall("", ExperienceDirectory.DEFAULT_MAX_ROWS, null, java.util.List.of(),
                                PresentationReceipt.SURFACE_DIRECTORY, false),
                        m.size(), m.stats().verified(), m.stats().generalized(),
                        m.loadStats(), m.stats(),
                        ExperienceDirectory.DEFAULT_MAX_ROWS,
                        ExperienceDirectory.DEFAULT_MAX_CHARS);
                return block.text();
            } catch (Throwable t) {
                // 目录是可选增强：坏了就这段留空，每轮上下文照常
                LOG.warn("[expmem] experience directory unavailable: {}", t.toString());
                return "";
            }
        });
        LOG.info("[expmem] experience memory plugin ready");
    }

    /** 取（或惰性创建）某同伴的经验记忆。 */
    public static ExperienceMemory memory(UUID companionId) {
        Path dir = configDir;
        if (dir == null) {
            throw new IllegalStateException("experience plugin not set up");
        }
        return MEMORIES.computeIfAbsent(companionId,
                uuid -> ExperienceMemory.at(
                        dir.resolve("experience-" + uuid + ".jsonl"),
                        new LexicalExperienceRetriever()));
    }

    /**
     * {@code config/numen/} 目录；未 setup 时为 null。
     *
     * <p><b>E7：{@code ExperienceVerifyTool} 只读列待回报清单时要读
     * {@code monitor/events.jsonl}（任务收尾结果）来给每条呈现配上判据，
     * 那是纯只读旁路，不经过 {@link #memory}。</b>返 null 而不是抛异常：
     * 读不到结果源时如实报「读不到」，不该让整个工具挂掉。</p>
     */
    static Path configDir() {
        return configDir;
    }
}
