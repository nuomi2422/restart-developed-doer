package com.dwinovo.numen.api;

/**
 * 为任务链规划请求贡献一段参考资料正文。
 *
 * <p>与 {@code contributeState} 同构：每次请求现算，没有可说的就返回空串或 {@code null}。
 * 区别只在于消费方——那份进的是同伴每轮的 {@code <runtime_state>}，这份进的是
 * <b>任务链规划器发给模型的请求正文</b>。
 *
 * <p>实现方必须自己保证：预算受限、只读、不抛异常。贡献失败不会拖垮规划——
 * 汇总侧会丢掉出问题的那一段，其余照常。
 */
@FunctionalInterface
public interface PlanningKnowledgeContributor {

    /** 返回要注入的知识正文；没有可注入的返回空串/{@code null}。 */
    String knowledgeFor(PlanningQuery query);
}
