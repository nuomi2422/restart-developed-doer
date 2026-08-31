package com.dwinovo.numen.experience.api;

import java.util.List;

/** 一次经验检索请求。{@code text} 是当前任务/失败/异常的自然语言描述。 */
public record ExperienceQuery(
        String text,
        int limit,
        ExperienceMaturity minMaturity,
        List<String> tags
) {

    public ExperienceQuery {
        if (text == null) {
            text = "";
        }
        if (limit <= 0) {
            limit = 5;
        }
        if (tags == null) {
            tags = List.of();
        }
        // minMaturity == null 意味着不设门槛（全部成熟度都算）。
    }

    public static ExperienceQuery of(String text, int limit) {
        return new ExperienceQuery(text, limit, null, List.of());
    }
}
