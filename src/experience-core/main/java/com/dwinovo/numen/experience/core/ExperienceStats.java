package com.dwinovo.numen.experience.core;

/** 经验库汇总统计，喂给监测台 / contributeState。 */
public record ExperienceStats(
        int total,
        int verified,
        int generalized
) {
}
