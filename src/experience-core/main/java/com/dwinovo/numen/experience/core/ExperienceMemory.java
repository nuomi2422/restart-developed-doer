package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceQuery;
import com.dwinovo.numen.experience.api.ExperienceRetriever;

import java.nio.file.Path;
import java.util.List;

/**
 * 经验记忆的门面：一个主人/同伴一份，负责“学”、“验”、“查”。
 *
 * <p>{@code at(file, retriever)} 构造：文件用于持久化，retriever 是检索接缝
 * （第一版用 {@link LexicalExperienceRetriever}，将来接向量记忆换它）。
 */
public final class ExperienceMemory {

    private final ExperienceStore store;
    private final ExperienceRetriever retriever;

    private ExperienceMemory(Path file, ExperienceRetriever retriever) {
        this.store = ExperienceStore.at(file);
        this.retriever = retriever;
    }

    public static ExperienceMemory at(Path file, ExperienceRetriever retriever) {
        return new ExperienceMemory(file, retriever);
    }

    /** 学一条经验（同 stable key 合并）。 */
    public ExperienceEntry learn(ExperienceEntry entry) {
        return store.learn(entry);
    }

    /**
     * 记录一次真实结果。返回更新后的经验；id 不存在返回 {@code null}。
     *
     * @see ExperienceStore#recordEvidence
     */
    public ExperienceEntry recordEvidence(String id, boolean success, String note) {
        return store.recordEvidence(id, success, note);
    }

    /** 查经验：按当前任务/失败/异常的自然语言检索相关经验。 */
    public List<ExperienceHit> recall(String text, int limit, ExperienceMaturity minMaturity, List<String> tags) {
        return retriever.retrieve(new ExperienceQuery(text, limit, minMaturity, tags), store.all());
    }

    /** 全量经验（调试/交接用）。 */
    public List<ExperienceEntry> all() {
        return store.all();
    }

    public int size() {
        return store.size();
    }

    public ExperienceStats stats() {
        int verified = 0;
        int generalized = 0;
        for (ExperienceEntry e : store.all()) {
            if (e.maturity() == ExperienceMaturity.GENERALIZED) {
                generalized++;
            }
            if (e.maturity() == ExperienceMaturity.VERIFIED) {
                verified++;
            }
        }
        return new ExperienceStats(store.size(), verified, generalized);
    }
}
