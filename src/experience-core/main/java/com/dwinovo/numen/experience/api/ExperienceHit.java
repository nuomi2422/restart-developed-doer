package com.dwinovo.numen.experience.api;

import java.util.List;

/** 一条被召回的命中的经验，附上得分与命中词，方便消费者（或 LLM）判断为什么被召回。 */
public record ExperienceHit(
        ExperienceEntry entry,
        double score,
        List<String> matchedTerms
) {
}
