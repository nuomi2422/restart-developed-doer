package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.PlanningQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 规划请求的知识注入：把宿主（经验插件）贡献的参考资料贴进<b>最终发给 LLM 的请求正文</b>。
 *
 * <h2>为什么必须贴在 userContent 上</h2>
 * RDD 的三个规划入口（Stage-A {@code planStages}、Stage-B {@code decomposeSpecs}、
 * 回退 {@code decompose}）都走 {@link RddDecomposer#llmAsk}，而 {@code llmAsk} 会把同一个
 * userContent 交给 {@code RddPlugin.publishPlanningContext} 观测出口和真正的
 * {@code chatStreaming}。所以只有贴在这个字符串上，才算「真的进了请求」——
 * 只写日志、只在适配器里拼好都不算接通。
 *
 * <h2>三条约束</h2>
 * <ul>
 *   <li><b>预算受限</b>：正文由知识侧限定（条目数 + 字符数），本类不再放大。</li>
 *   <li><b>安全降级</b>：知识源没装、读失败、返回空、抛异常，一律退回「无知识」的原始请求，
 *       规划照常发出与执行。</li>
 *   <li><b>可观测但不留全文</b>：发布紧凑事件（是否注入 / 字符数 / 阶段），
 *       正文本身由 {@code llm_request} 的 request 正文承载，避免重复记录无界文本。</li>
 * </ul>
 */
final class RddPlanningKnowledge {

    /** 知识提供者接缝：生产中走宿主 API，测试注入 stub。 */
    @FunctionalInterface
    interface Provider {
        String knowledgeFor(UUID companionId, String objective, String stage, List<String> knownFailures);
    }

    /**
     * 生产实现：向宿主要「所有插件为这次规划贡献的知识」。
     * 规划器与知识插件互不可见，只能走这扇门；任何异常都退化成「没有知识」。
     */
    static final Provider HOST = (companionId, objective, stage, knownFailures) -> {
        try {
            return NumenPlugins.planningKnowledge(
                    PlanningQuery.of(companionId, objective, stage, knownFailures));
        } catch (Throwable t) {
            return "";
        }
    };

    private RddPlanningKnowledge() {}

    /**
     * 纯函数：把知识块贴到基础提示之后，形成最终请求正文。
     * 没有知识时<b>原样返回</b>——不插入空标题、不插占位符，避免污染 prompt。
     */
    static String attach(String basePrompt, String knowledge) {
        boolean hasKnowledge = knowledge != null && !knowledge.isBlank();
        if (basePrompt == null || basePrompt.isEmpty()) {
            // 没有基础提示时不要留下孤立的前导换行
            return hasKnowledge ? knowledge : "";
        }
        if (!hasKnowledge) {
            return basePrompt;
        }
        return basePrompt + "\n" + knowledge;
    }

    /**
     * 取知识 → 贴进请求 → 记观测。这是三个规划入口共用的接线点。
     *
     * @param basePrompt 规划器原本要发出去的正文（背包/已完成阶段/重试提示都已渲染好）
     * @return 最终请求正文；知识不可用时与 {@code basePrompt} 逐字相同
     */
    static String withKnowledge(Provider provider, UUID companionId, String basePrompt,
                                String objective, String stage, List<String> knownFailures) {
        Provider p = provider == null ? HOST : provider;
        String knowledge = "";
        try {
            knowledge = p.knowledgeFor(companionId, objective, stage, knownFailures);
        } catch (Throwable t) {
            knowledge = ""; // 知识是可选增强，取不到就当没有
        }
        String request = attach(basePrompt, knowledge);
        publish(companionId, stage, knowledge, request);
        return request;
    }

    /**
     * 紧凑观测：规划策略摘要 + 守门结果。只记<b>决策结果</b>（适用了哪些规则、丢了什么、
     * 复用了什么），不记任何隐藏推理，也不记正文全文。
     */
    static void publishPolicy(UUID companionId, String stage, List<String> rules,
                              List<String> dropped, List<String> reused) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companionId", companionId == null ? "" : companionId.toString());
            data.put("stage", stage);
            data.put("rules", rules == null ? List.of() : rules);
            data.put("dropped", dropped == null ? List.of() : dropped);
            data.put("reused", reused == null ? List.of() : reused);
            RddMonitor.publish("planning_policy", data);
        } catch (Throwable ignored) {
            // 观测失败不影响规划
        }
    }

    /** 紧凑观测：只报「注没注、注了多少、哪个阶段」，不重复记录正文全文。 */
    private static void publish(UUID companionId, String stage, String knowledge, String request) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companionId", companionId == null ? "" : companionId.toString());
            data.put("stage", stage);
            data.put("injected", knowledge != null && !knowledge.isBlank());
            data.put("knowledgeChars", knowledge == null ? 0 : knowledge.length());
            data.put("requestChars", request == null ? 0 : request.length());
            RddMonitor.publish("planning_knowledge", data);
        } catch (Throwable ignored) {
            // 观测失败不影响规划（含宿主环境缺类等 Error）
        }
    }
}
