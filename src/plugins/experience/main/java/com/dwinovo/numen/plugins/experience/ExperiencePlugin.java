package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.ExperienceStats;
import com.dwinovo.numen.experience.core.LexicalExperienceRetriever;
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
            ExperienceStats s = m.stats();
            if (s.total() == 0) {
                return "";
            }
            return "<experience><total>" + s.total() + "</total>"
                    + "<verified>" + s.verified() + "</verified>"
                    + "<generalized>" + s.generalized() + "</generalized></experience>";
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
}
