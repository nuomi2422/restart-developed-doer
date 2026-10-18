package com.dwinovo.numen.rdd.core;

/**
 * 死因归类器（2026-10-01，纯函数）：把原版 {@code DamageSource} 的信息压成一个
 * <b>机器可聚合</b>的短标签。
 *
 * <p><b>为什么需要它</b>：死因在系统里本来就一路存在（{@code NumenPlayer.die} 抄下、
 * {@code NumenPlayer.die → deathMessage}、{@code EntityAgentLoop.onEntityDied} 送客户端、
 * 复活时进 {@code <event>}），但**下埋点的那个消费方拿不到它**：
 * {@code RddPlugin.onCompanionDeath} 只有 {@code NumenPlayer}，而当时只有渲染后的中文句子，
 * 于是 {@code reason} 只能塌缩成 {@code starvation} / {@code other} 二值。
 *
 * <p><b>为什么不直接解析那句中文</b>：2026-10-01 实测日志里死因形态只有 5 种，全是渲染后的句子：
 * <pre>
 *   579  rdd被杀死了              ← 无凶手兜底（48%）
 *   465  rdd被maoshao2422杀死了    ← 被玩家杀（38%）
 *   102  rdd被骷髅射杀
 *    34  rdd被苦力怕炸死了
 *    34  rdd被女巫使用的魔法杀死了
 *   岩浆 / 淹死 / 摔死：0 条
 * </pre>
 * 按 id 设计的规则（{@code death.attack.lava} 之类）一条都不会命中；按中文写规则则**换语言即失效**。
 * 所以入参以 <b>id 为主、中文句子为兜底</b>。
 *
 * <p><b>两条硬约束</b>（都因为调用点在死亡处理链上）：
 * <ol>
 *   <li><b>永不抛异常</b>。归类失败绝不能阻断死亡处理 —— {@code onCompanionDeath} 整体在
 *       try/catch 里，但抛异常会连带丢掉 {@code RddRepairDispatch.onDeath} 的捡包支线生成。</li>
 *   <li><b>{@code OTHER} 不吞信息</b>。埋点里同时带原串/原 id，新形态靠数据补规则，不靠猜。</li>
 * </ol>
 */
public final class DeathCauseClassifier {

    /** 熔岩 / 火。 */
    public static final String LAVA = "lava";
    /** 溺水。 */
    public static final String DROWN = "drown";
    /** 摔落。 */
    public static final String FALL = "fall";
    /** 窒息（墙里 / 仙人掌 / 屏障）。 */
    public static final String SUFFOCATE = "suffocate";
    /** 仙人掌（与窒息分开：它是「自己走错了」，经验不同）。 */
    public static final String CACTUS = "cactus";
    /** 被怪物 / 投射物打中。 */
    public static final String MOB = "mob";
    /** 被玩家打中 —— 归因上与 MOB 相反：这是主人插手，不是她打不过。 */
    public static final String PLAYER = "player";
    /** 虚空。 */
    public static final String VOID = "void";
    /** 饿死。 */
    public static final String STARVATION = "starvation";
    /** 爆炸（TNT / 苦力怕）。 */
    public static final String EXPLOSION = "explosion";
    /** 雷击。 */
    public static final String LIGHTNING = "lightning";
    /** 有死亡事件但拿不到任何来源信息（原版兜底）。 */
    public static final String UNKNOWN = "unknown";
    /** 确实认得的 id，只是本表没覆盖 —— 原串会一起进埋点，便于事后补规则。 */
    public static final String OTHER = "other";

    private DeathCauseClassifier() {}

    /**
     * 归类。<b>永不抛异常、永不返回 null。</b>
     *
     * @param causeId    {@code DamageSource#getMsgId()}，如 {@code lava}；可为空
     * @param deathMessage 渲染后的死亡句子（如 {@code rdd被骷髅射杀}）；可为空，兜底用
     * @return 见本类常量之一
     */
    public static String classify(String causeId, String deathMessage) {
        try {
// 以 id 为主：与语言无关，是原版事实。
            String id = causeId == null ? "" : causeId.trim().toLowerCase(java.util.Locale.ROOT);
            String byId = classifyId(id);
            if (!UNKNOWN.equals(byId) && !OTHER.equals(byId)) {
                return byId;
            }
            // id 缺失或不认得，退到中文句子（换语言会退化，但永不失效）。
            String byMsg = classifyMessage(deathMessage);
            if (!UNKNOWN.equals(byMsg)) {
                return byMsg;
            }
            // 句子也没线索 → 保留 id 那一侧的判断。
            // ★ 这里不能一律返回 UNKNOWN：OTHER 的全部价值就是「见到一个本表没覆盖的
            // 死因类型」。它若被 UNKNOWN 吞掉，就再也没法从日志里发现新类型、也没法补规则了
            // （OTHER 的设计前提就是「原串会一起进埋点」，前提是它得先活到埋点那一步）。
            return byId;
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    /** 只按 id 判。认不得返回 {@link #OTHER}（区别于「什么都没有」的 {@link #UNKNOWN}）。 */
    static String classifyId(String id) {
        if (id == null || id.isBlank()) return UNKNOWN;
        // 顺序要紧：先认具体的，再认泛的。
        //   suffocate 要排在 mob/attack 类之前吗？不冲突（关键词互斥），
        //   但 explosion 要排在 mob_attack 之前 —— 苦力怕的 msgId 是 explosion，语义上是环境。
        if (id.contains("in_wall") || id.contains("suffocat")) return SUFFOCATE;
        if (id.contains("cactus")) return CACTUS;
        if (id.contains("lava") || id.contains("in_fire") || id.contains("fire")
                || id.contains("hot_touch")) return LAVA;
        if (id.contains("drown")) return DROWN;
        if (id.contains("fall")) return FALL;
        if (id.contains("void") || id.contains("out_of_world")) return VOID;
        if (id.contains("starve")) return STARVATION;
        if (id.contains("lightning")) return LIGHTNING;
        // 爆炸放在 mob_attack 之前：苦力怕/点燃 TNT 的 msgId 就是 explosion。
        if (id.contains("explosion")) return EXPLOSION;
        // 玩家攻击（含 player_attack / 玩家投射物）—— 必须排在 mob 之前，
        // 但 projectile 类不是玩家，所以先看 player。
        if (id.contains("player")) return PLAYER;
        if (id.contains("mob") || id.contains("arrow") || id.contains("entity")
                || id.contains("attack") || id.contains("zombie") || id.contains("skeleton")
                || id.contains("creeper") || id.contains("spider") || id.contains("witch")) return MOB;
        if (id.contains("generic")) return UNKNOWN;
        return OTHER;
    }

    /**
     * 只按渲染后的句子判（{@code causeId} 缺失/不认得的兜底）。
     *
     * <p>句式来自实测样本：{@code rdd被骷髅射杀}、{@code rdd被苦力怕炸死了}、
     * {@code rdd被女巫使用的魔法杀死了}、{@code rdd被maoshao2422杀死了}、{@code rdd被杀死了}。
     * <b>无凶手兜底（只有「被杀死了」没有「被X」）→ {@link #UNKNOWN}</b>，
     * 不是 {@link #OTHER}：那不是「规则没覆盖」，那是原版没给出凶手。
     */
    static String classifyMessage(String msg) {
        if (msg == null) return UNKNOWN;
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        if (m.isBlank()) return UNKNOWN;

        // 环境类（中文词形；英文也一并认，省得换语言直接失效）
        if (containsAny(m, "岩浆", "熔岩", "lava", "烧死", "烧死")) return LAVA;
        if (containsAny(m, "溺", "淹", "drown", "water")) return DROWN;
        if (containsAny(m, "摔", "坠落", "fell", "fall")) return FALL;
        if (containsAny(m, "窒息", "墙里", "suffocat")) return SUFFOCATE;
        if (containsAny(m, "仙人掌", "cactus")) return CACTUS;
        if (containsAny(m, "虚空", "void")) return VOID;
        if (containsAny(m, "饿", "starv")) return STARVATION;
        if (containsAny(m, "炸", "爆炸", "explosion", "tnt")) return EXPLOSION;
        if (containsAny(m, "雷", "lightning")) return LIGHTNING;

        // 怪物词表（实测出现过 骷髅/苦力怕/女巫；其余常见被动怪一并收进来）
        if (containsAny(m, "骷髅", "苦力怕", "女巫", "僵尸", "蜘蛛", "史莱姆", " Creeper", "creeper",
                "skeleton", "zombie", "witch", "spider", "slime", "末影", "ENDER", "恶魂",
                "凋灵", "卫道士", "猪灵", "蛮兵")) return MOB;

        // 玩家：句式是「被<凶手名字>杀死」。**必须有凶手名字**。
        //
        // ★ 2026-10-01 修正：原先只判「含 被 且含 杀死」，结果实测里占 48% 的无凶手兜底
        // 「rdd被杀死了」也命中 —— 被 与 杀死 之间 0 个字。一半的真实死亡会被错标成
        // 「被玩家杀」，而归因方向完全相反（玩家是主人插手，不是她打不过）。
        // 所以改成正则抓 被…杀死 中间那段，非空才算有凶手。
        java.util.regex.Matcher killedBy = KILLED_BY.matcher(m);
        if (killedBy.find() && killedBy.group(1) != null && !killedBy.group(1).isBlank()) {
            return PLAYER;
        }

        // 什么都没匹配上，但确实有句子 —— 不猜成任何具体原因。
        return UNKNOWN;
    }

    /**
     * 「被&lt;凶手&gt;杀死/打死/射杀/炸死」。
     *
     * <p>{@code 被被杀死了} 匹配不到 group(1)（中间是空的）→ 不判 PLAYER，正确落 UNKNOWN。
     */
    private static final java.util.regex.Pattern KILLED_BY =
            java.util.regex.Pattern.compile("被(.+?)(?:杀死|打死|射杀|炸死|烧死|淹死|摔死)");

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (n != null && !n.isEmpty() && haystack.contains(n.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
