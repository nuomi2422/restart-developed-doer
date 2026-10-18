package com.dwinovo.numen.plugins.selfcompile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * P5.2 权限分级：把"谁能对自变异系统做什么"从代码里抽出来，按 UUID → {@link Tier} 配置。
 *
 * <p>三档（低 → 高）：{@code OBSERVER < CONTRIBUTOR < MAINTAINER}。未列出者缺省 {@code OBSERVER}
 * （最小权限）。动作所需最低档：{@code VIEW=OBSERVER}；{@code PROPOSE=CONTRIBUTOR}；
 * {@code APPROVE/EXECUTE/DEPLOY=MAINTAINER}。
 *
 * <p>配置文件 {@code config/selfcompile/permissions.csv}，每行 {@code TIER,uuid}，
 * 忽略空行与 {@code #} 开头注释：
 * <pre>
 * MAINTAINER,11111111-1111-1111-1111-111111111111
 * CONTRIBUTOR,22222222-2222-2222-2222-222222222222
 * </pre>
 * 文件缺失/损坏 → 空表（全 OBSERVER，安全默认，绝不因坏配置放开权限）。
 *
 * <p>纯逻辑、无外部依赖：便于单测，也便于之后在工具/服务层按调用者身份强制。
 */
public final class SelfCompilePermissions {

    /** 权限档位，序数越大权限越高。 */
    public enum Tier { OBSERVER, CONTRIBUTOR, MAINTAINER }

    /** 对自变异系统可做的动作。 */
    public enum Action { VIEW, PROPOSE, APPROVE, EXECUTE, DEPLOY }

    private static final Map<Action, Tier> REQUIRED = new EnumMap<>(Action.class);

    static {
        REQUIRED.put(Action.VIEW, Tier.OBSERVER);
        REQUIRED.put(Action.PROPOSE, Tier.CONTRIBUTOR);
        REQUIRED.put(Action.APPROVE, Tier.MAINTAINER);
        REQUIRED.put(Action.EXECUTE, Tier.MAINTAINER);
        REQUIRED.put(Action.DEPLOY, Tier.MAINTAINER);
    }

    private final Map<UUID, Tier> tiers = new HashMap<>();

    /** 动作所需最低档；未知动作按最严 MAINTAINER 处理。 */
    public static Tier required(Action action) {
        return REQUIRED.getOrDefault(action, Tier.MAINTAINER);
    }

    /** 某档位是否够格做某动作。 */
    public static boolean allowed(Tier actor, Action action) {
        return actor != null && action != null && actor.ordinal() >= required(action).ordinal();
    }

    /** 某 UUID 的档位；未知/ null → OBSERVER。 */
    public Tier tierOf(UUID who) {
        if (who == null) return Tier.OBSERVER;
        return tiers.getOrDefault(who, Tier.OBSERVER);
    }

    /** 某 UUID 是否够格做某动作；null 身份一律拒绝（fail closed）。 */
    public boolean allowed(UUID who, Action action) {
        if (who == null) return false;
        return allowed(tierOf(who), action);
    }

    /** 显式授予（程序化配置 / 测试用）；null 直接忽略。 */
    public void grant(UUID who, Tier tier) {
        if (who == null || tier == null) return;
        tiers.put(who, tier);
    }

    public int size() {
        return tiers.size();
    }

    /** 从 CSV 载入；文件缺失/损坏 → 空表（全 OBSERVER）。单个坏行只跳过该行。 */
    public static SelfCompilePermissions load(Path file) {
        SelfCompilePermissions perms = new SelfCompilePermissions();
        if (file == null || !Files.isRegularFile(file)) return perms;
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return perms;
        }
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int comma = line.indexOf(',');
            if (comma <= 0) continue;                       // 没有分隔符 / 档位为空
            Tier tier;
            try {
                tier = Tier.valueOf(line.substring(0, comma).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknownTier) {
                continue;                                   // 未知档位不授予
            }
            try {
                perms.grant(UUID.fromString(line.substring(comma + 1).trim()), tier);
            } catch (IllegalArgumentException badUuid) {
                // 坏 UUID 只跳过这一行，不影响其余条目
            }
        }
        return perms;
    }
}
