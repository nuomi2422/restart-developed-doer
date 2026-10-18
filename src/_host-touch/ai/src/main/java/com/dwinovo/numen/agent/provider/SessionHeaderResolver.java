package com.dwinovo.numen.agent.provider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F3：把 provider 配置里的<b>会话占位符</b>解成每个请求自己的会话头。
 *
 * <h2>为什么需要</h2>
 * {@code numen_providers.json} 的 {@code headers} 是 <b>provider 级静态</b> 的：
 * {@code "x-opencode-session": "numen-minecraft"} 对所有同伴是同一个值。
 * 于是同一个网关看到的是"一个会话"，而不是"N 个同伴各自的会话"。
 *
 * <h2>规则（刻意保守）</h2>
 * <ul>
 *   <li><b>只有</b>值里含 {@value #SESSION_PLACEHOLDER} 时才替换；没含就<b>原样保留</b>。
 *       这样 16 个 provider 的现有配置一个字节都不用改，对没有会话概念的站点零影响。</li>
 *   <li>{@code session} 为 null/空（设置面板 ping 这类没有同伴身份的调用）→ 不替换，
 *       保持静态值，<b>不</b>猜、不炸。</li>
 *   <li>纯函数、无 IO、可单测；不碰密钥。</li>
 * </ul>
 */
public final class SessionHeaderResolver {

    private SessionHeaderResolver() {}

    /** 配置值里出现这个字面量才启用按会话替换。 */
    public static final String SESSION_PLACEHOLDER = "${session}";

    /**
     * 解析会话头。
     *
     * @param headers  provider 的静态头（来自配置）
     * @param session  本次请求的会话标识（同伴 id 派生）；null = 无会话身份
     * @return 需要在<b>本次请求</b>上覆盖的头；没有需要覆盖的返回空表
     */
    public static Map<String, String> resolve(Map<String, String> headers, String session) {
        if (headers == null || headers.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || key.isBlank() || value == null) continue;
            if (!value.contains(SESSION_PLACEHOLDER)) continue;   // 不含占位符 = 保持静态值
            // 头值一律经 sessionValue 生成（它负责截短 id）：这是唯一的取值口径，
            // 免得"某条路径塞完整 uuid、某条路径塞短 id"导致网关看到两个不同的会话。
            String resolved = sessionValue(value, session);
            out.put(key, resolved == null ? value : resolved);
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    /**
     * 把配置值里可读的会话前缀与同伴 id 拼成头值。
     *
     * <p>取 {@code numen-<uuid 前 8 位>}：够区分同伴，又不把完整 uuid 写进网关日志。
     * 前缀从配置值里 {@link #SESSION_PLACEHOLDER} 之前的部分直接复用，
     * 所以 {@code "numen-minecraft-${session}"} 会得到 {@code numen-minecraft-3f2a91bc}。
     *
     * @param staticValue 该头的配置值（须含占位符）
     * @param companionId 同伴 uuid 字符串；null/空 → null（调用方保持静态值）
     */
    public static String sessionValue(String staticValue, String companionId) {
        if (staticValue == null || companionId == null || companionId.isBlank()) return null;
        if (!staticValue.contains(SESSION_PLACEHOLDER)) return null;
        String prefix = staticValue.substring(0, staticValue.indexOf(SESSION_PLACEHOLDER));
        String shortId = companionId.length() > 8 ? companionId.substring(0, 8) : companionId;
        return prefix + shortId;
    }
}