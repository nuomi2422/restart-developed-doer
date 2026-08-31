package com.dwinovo.numen.experience.api;

import java.util.List;

/**
 * 经验检索的接缝。
 *
 * <p>第一版用 {@link com.dwinovo.numen.experience.core.LexicalExperienceRetriever}
 * （纯词法，零外部依赖）。这个接口就是将来接向量记忆的地方——ChatCore/Numen
 * 已有向量检索能力时，写一个 {@code VectorExperienceRetriever} 代理过去即可，
 * 经验模型与消费方都不用改。
 */
public interface ExperienceRetriever {

    /**
     * @param query   检索请求（文本 + 门槛 + 标签过滤）
     * @param entries 候选经验集合（通常来自 {@code ExperienceStore.all()}）
     * @return 按得分降序的命中，最多 {@code query.limit()} 条
     */
    List<ExperienceHit> retrieve(ExperienceQuery query, List<ExperienceEntry> entries);
}
