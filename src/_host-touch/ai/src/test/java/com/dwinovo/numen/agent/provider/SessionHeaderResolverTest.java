package com.dwinovo.numen.agent.provider;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3：{@code x-opencode-session} 按同伴隔离。
 *
 * <p>红线级约束：<b>不含占位符的头值必须逐字不变</b>。
 * {@code numen_providers.json} 里有 16 个 provider，只有 opencode-go 用会话头；
 * 任何"顺手给所有头都套上会话值"的做法都会污染其余站点的请求。
 */
class SessionHeaderResolverTest {

    private static final String SESSION_HEADER = "x-opencode-session";
    private static final String PLACEHOLDER = SessionHeaderResolver.SESSION_PLACEHOLDER;

    // ---- 占位符替换 ----

    @Test
    void resolvesPlaceholderIntoPerCompanionValue() {
        Map<String, String> configured = Map.of("User-Agent", "numen-minecraft-agent/1.0",
                SESSION_HEADER, "numen-minecraft-" + PLACEHOLDER);
        Map<String, String> resolved = SessionHeaderResolver.resolve(configured,
                "3f2a91bc-1111-2222-3333-444455556666");
        assertEquals("numen-minecraft-3f2a91bc", resolved.get(SESSION_HEADER));
        assertFalse(resolved.containsKey("User-Agent"), "旁触无关的头不得被覆盖");
    }

    @Test
    void twoCompaniesGetDifferentSessions() {
        Map<String, String> configured = Map.of(SESSION_HEADER, "numen-minecraft-" + PLACEHOLDER);
        String a = SessionHeaderResolver.resolve(configured, "aaaaaaaa-0000").get(SESSION_HEADER);
        String b = SessionHeaderResolver.resolve(configured, "bbbbbbbb-0000").get(SESSION_HEADER);
        assertFalse(a.equals(b), "两个同伴必须拿到不同的会话值（这正是要修的串味）");
    }

    @Test
    void sameCompanionGetsStableSessionAcrossTurns() {
        Map<String, String> configured = Map.of(SESSION_HEADER, "numen-minecraft-" + PLACEHOLDER);
        String first = SessionHeaderResolver.resolve(configured, "aaaaaaaa-0000").get(SESSION_HEADER);
        String second = SessionHeaderResolver.resolve(configured, "aaaaaaaa-0000").get(SESSION_HEADER);
        String third = SessionHeaderResolver.resolve(configured, "aaaaaaaa-0000").get(SESSION_HEADER);
        assertEquals(first, second);
        assertEquals(second, third, "同一同伴连续请求不得抖动（抖动会让网关当成新会话）");
    }

    // ---- 不含占位符：一律不动 ----

    @Test
    void headerWithoutPlaceholderIsLeftAlone() {
        Map<String, String> configured = Map.of(SESSION_HEADER, "numen-minecraft");
        assertTrue(SessionHeaderResolver.resolve(configured, "aaaaaaaa-0000").isEmpty(),
                "没有占位符 = 用户没要求按会话隔离，一个字节都不许改");
    }

    @Test
    void otherProvidersAreUntouched() {
        // openrouter 那种带 Referer/X-Title 的站点
        Map<String, String> openrouter = new LinkedHashMap<>();
        openrouter.put("HTTP-Referer", "https://github.com/Dwinovo/Numen");
        openrouter.put("X-Title", "Numen (Minecraft)");
        assertTrue(SessionHeaderResolver.resolve(openrouter, "aaaaaaaa-0000").isEmpty());
    }

    // ---- 无会话身份：保持配置值，不猜 ----

    @Test
    void nullSessionKeepsConfiguredLiteral() {
        Map<String, String> configured = Map.of(SESSION_HEADER, "numen-minecraft-" + PLACEHOLDER);
        Map<String, String> resolved = SessionHeaderResolver.resolve(configured, null);
        assertEquals("numen-minecraft-" + PLACEHOLDER, resolved.get(SESSION_HEADER),
                "没有同伴身份（设置面板 ping）时保持配置字面值，不得把占位符发到网关");
    }

    @Test
    void blankSessionKeepsConfiguredLiteral() {
        Map<String, String> configured = Map.of(SESSION_HEADER, "x-" + PLACEHOLDER);
        assertEquals("x-" + PLACEHOLDER,
                SessionHeaderResolver.resolve(configured, "   ").get(SESSION_HEADER));
    }

    @Test
    void emptyOrNullHeadersResolveToEmpty() {
        assertTrue(SessionHeaderResolver.resolve(null, "aaaaaaaa").isEmpty());
        assertTrue(SessionHeaderResolver.resolve(Map.of(), "aaaaaaaa").isEmpty());
    }

    @Test
    void malformedEntriesAreSkippedNotThrown() {
        Map<String, String> dirty = new LinkedHashMap<>();
        dirty.put("  ", "x-" + PLACEHOLDER);
        dirty.put(SESSION_HEADER, null);
        Map<String, String> good = Map.of(SESSION_HEADER, "y-" + PLACEHOLDER);
        dirty.putAll(good);
        Map<String, String> resolved = SessionHeaderResolver.resolve(dirty, "aaaaaaaa");
        assertEquals(1, resolved.size(), "脏条目跳过，好条目照常替换");
        assertEquals("y-aaaaaaaa", resolved.get(SESSION_HEADER));
    }

    // ---- sessionValue：前缀与短 id ----

    @Test
    void sessionValueReusesConfiguredPrefix() {
        assertEquals("numen-minecraft-3f2a91bc",
                SessionHeaderResolver.sessionValue("numen-minecraft-" + PLACEHOLDER, "3f2a91bc-1111"));
        assertEquals("gpt-3f2a91bc",
                SessionHeaderResolver.sessionValue("gpt-" + PLACEHOLDER, "3f2a91bc-1111"));
    }

    @Test
    void sessionValueReturnsNullWhenItCannotDerive() {
        assertNull(SessionHeaderResolver.sessionValue("numen-minecraft", "3f2a91bc"),
                "没有占位符就不该造会话值");
        assertNull(SessionHeaderResolver.sessionValue("numen-" + PLACEHOLDER, null));
        assertNull(SessionHeaderResolver.sessionValue(null, "3f2a91bc"));
    }

    @Test
    void shortIdsAreUsedNotFullUuids() {
        String value = SessionHeaderResolver.sessionValue("numen-" + PLACEHOLDER,
                "3f2a91bc-1111-2222-3333-444455556666");
        assertFalse(value.contains("444455556666"), "不要把完整 uuid 写进网关日志：");
        assertEquals("numen-3f2a91bc", value);
    }

    @Test
    void veryShortIdIsNotTruncatedIntoInvalid() {
        assertEquals("numen-abc", SessionHeaderResolver.sessionValue("numen-" + PLACEHOLDER, "abc"));
    }

    // ---- 真配置联动：opencode-go 必须真的能按同伴解析 ----

    /**
     * 网关要的是"能路由的会话 id"（{@code 400 MissingSessionID}）。
     * 配置里若忘了写占位符，本测试会红 —— 那种情况下所有同伴仍然共用一个会话。
     */
    @Test
    void shippedOpenCodeGoConfigResolvesToPerCompanionSessions() {
        var configured = ProviderRegistry.headers("opencode-go");
        String raw = configured.get(SESSION_HEADER);
        assertTrue(raw != null && raw.contains(PLACEHOLDER),
                "opencode-go 的 x-opencode-session 必须含 " + PLACEHOLDER + "，否则多同伴共用一个会话；实际=" + raw);
        String resolved = SessionHeaderResolver.sessionValue(raw, "3f2a91bc-1111-2222-3333-444455556666");
        assertTrue(resolved != null && !resolved.isBlank() && !resolved.contains(PLACEHOLDER),
                "解析后不得把占位符原样发给网关；实际=" + resolved);
    }
}