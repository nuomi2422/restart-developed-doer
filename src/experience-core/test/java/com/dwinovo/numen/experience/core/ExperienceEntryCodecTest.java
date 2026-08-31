package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceEntryCodecTest {

    @Test
    void roundTripsThroughJson() {
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.TOOL_DEFECT)
                .title("假成功")
                .description("工具返回OK但世界没变")
                .rationale("防止AI被工具自述骗")
                .rootCause("工具只确认调用无异常")
                .recommendedResponse("操作后重新观察")
                .triggerStrings(List.of("假成功", "世界没变"))
                .toolNames(List.of("smelt_item"))
                .tags(List.of("world"))
                .maturity(ExperienceMaturity.VERIFIED)
                .verifiedCount(2)
                .priority(90)
                .counterexamples(List.of("有一次其实成功了"))
                .build();

        ExperienceEntry back = ExperienceEntry.fromJson(
                JsonParser.parseString(e.toJson().toString()).getAsJsonObject());
        assertEquals(e.id(), back.id());
        assertEquals(e.type(), back.type());
        assertEquals(e.title(), back.title());
        assertEquals(e.description(), back.description());
        assertEquals(e.rationale(), back.rationale());
        assertEquals(e.rootCause(), back.rootCause());
        assertEquals(e.recommendedResponse(), back.recommendedResponse());
        assertEquals(e.triggerStrings(), back.triggerStrings());
        assertEquals(e.toolNames(), back.toolNames());
        assertEquals(e.tags(), back.tags());
        assertEquals(e.maturity(), back.maturity());
        assertEquals(e.verifiedCount(), back.verifiedCount());
        assertEquals(e.priority(), back.priority());
        assertEquals(e.counterexamples(), back.counterexamples());
        assertEquals(e.createdAt(), back.createdAt());
        assertEquals(e.verifiedAt(), back.verifiedAt());
    }

    @Test
    void fromJsonToleratesMissingFields() {
        ExperienceEntry e = ExperienceEntry.fromJson(new JsonObject());
        assertEquals(ExperienceMaturity.OBSERVED, e.maturity());
        assertEquals(50, e.priority());
        assertTrue(e.triggerStrings().isEmpty());
        assertNull(e.type());
    }

    @Test
    void buildAssignsStableIdAndNormalizesBlanks() {
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.POLICY)
                .title(" 先查后写  ")
                .description("  动手前先确认问题没被解决过  ")
                .build();
        assertEquals(ExperienceEntry.stableKey(ExperienceType.POLICY, " 先查后写  "), e.id());
        assertEquals("先查后写", e.title());
        assertEquals("动手前先确认问题没被解决过", e.description());
    }
}
