package com.dwinovo.numen.experience.api;

/**
 * 一条经验属于哪种。分类不是给界面看的装饰，它决定经验在未来以什么身份被召回、
 * 被注入到哪个消费者（聊天 AI / 任务链 / 监测台）。
 */
public enum ExperienceType {
    /** 某类任务/操作应该如何完成。 */
    EXECUTION,
    /** 某类失败的现象、根因和恢复方式。 */
    FAILURE,
    /** 工具假成功、参数歧义、报错模糊等已知缺陷模式。 */
    TOOL_DEFECT,
    /** 物品、地点、前置条件之间的世界关系。 */
    WORLD_RELATION,
    /** 遇到某类情况时应采用的判断规则（“铁律”式经验）。 */
    POLICY
}
