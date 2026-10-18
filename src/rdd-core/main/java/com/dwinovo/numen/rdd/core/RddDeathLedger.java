package com.dwinovo.numen.rdd.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡台账：<b>每一次死亡单独一行，带掉落物的到期时刻</b>。
 *
 * <h2>为什么要有（2026-09-29 实机）</h2>
 * 旧实现只在死亡瞬间发一条 {@code companion_assets_invalidated}{count}，
 * 于是产生两个真 bug：
 * <ol>
 *   <li><b>它以为东西已经没了</b>：死亡后 5 分钟内掉落物<b>还在地上</b>，
 *       但资产已被标失效，没有任何人告诉它"还剩 200 秒，快去捡" →
 *       它按"东西没了"重新规划。</li>
 *   <li><b>连死两次就分不清了</b>：实机 15:50:53 死在 (200,51,-130)，
 *       15:51:09 又死在 (134,31,-96)，相隔 16 秒。两个掉落点的到期时刻不同，
 *       旧实现只留"最后一条"，于是"哪一次的掉落已经过期"根本无法回答。</li>
 * </ol>
 *
 * <h2>时间基准</h2>
 * 掉落物 despawn 按<b>游戏时间</b>，不是墙钟 —— 退出重进世界不加载时游戏时间不前进，
 * 掉落物也就不会消失。本类因此同时记 {@code gameTime}（用于到期判定，与原版同口径）
 * 和 {@code wallClockMillis}（仅供人看/排序）。到期判定只认 gameTime。
 *
 * <h2>有界</h2>
 * 每个同伴最多留 {@link #MAX_ENTRIES} 条（默认 8），按 gameTime 淘汰最老的。
 * 理由：连续死亡是常态，无界增长会让监测台 payload 膨胀到没法看。
 */
public final class RddDeathLedger {

    /**
     * 原版掉落物 despawn 时长：<b>5 分钟</b>。
     *
     * <p>必须是 {@code 20 tick/s * 60 s * 5 min = 6000}。第一版误写成
     * {@code 20 * 60}（=1200 tick = 1 分钟），单测直接量出 "还剩 60 秒" 才发现 ——
     * 窗口缩水到 1/5，她会在还有 4 分钟的时候就以为东西没了。
     */
    public static final long DROPS_LIVE_TICKS = 20L * 60L * 5L;

    /** 每个同伴保留的死亡记录条数上限（连续死亡是常态，无界会让 payload 膨胀）。 */
    public static final int MAX_ENTRIES = 8;

    /** 一次死亡的证据状态（2026-09-30 深审 R07）。 */
    public enum State {
        /** 只有时钟估计，没有真实证据 —— 默认落在这一档。 */
        UNVERIFIED,
        /** 已确认丢失：扫描确认不在 / 被销毁 / 已被墓碑模组收殓。 */
        CONFIRMED_LOST,
        /** 已确认捡回：掉落物还在而且已经拿回背包。 */
        RECOVERED
    }

    /**
     * 一次死亡。字段全 final：记录一经写下就不该被"修正"。
     *
     * <p>{@code state} 是 2026-09-30 深审 R07 加的：<b>「超时」不等于「没了」</b>。
     * 原版只在掉落物所在区块被 tick 时给它计龄，所以"过了 5 分钟"完全可能只是
     * "那边没加载"。没有这一档区分，{@link #render} 就只能说谎。
     */
    public record Death(int seq, long gameTime, long wallClockMillis,
                        String deathAt, int lostEntries,
                        State state, String evidence,
                        Map<String, Integer> lostItems) {

        public Death(int seq, long gameTime, long wallClockMillis,
                     String deathAt, int lostEntries) {
            this(seq, gameTime, wallClockMillis, deathAt, lostEntries, State.UNVERIFIED, "", null);
        }

        /** 旧构造器 + 证据状态（2026-09-30 深审 R07 之前写出的调用点形状，保持可用）。 */
        public Death(int seq, long gameTime, long wallClockMillis,
                     String deathAt, int lostEntries, State state, String evidence) {
            this(seq, gameTime, wallClockMillis, deathAt, lostEntries, state, evidence, null);
        }

        /** 本次死亡丢了哪些、各多少（可能为 null = 本次改动前的旧批次，无法逐项核对）。 */
        public Map<String, Integer> lostItems() {
            return lostItems == null ? Map.of() : lostItems;
        }

        /** 这一次的掉落物在 {@code nowGameTime} 时是否<b>可能</b>还在地上（纯估计）。 */
        public boolean stillRecoverable(long nowGameTime) {
            return state == State.UNVERIFIED && nowGameTime - gameTime < DROPS_LIVE_TICKS;
        }

        /** 距离<b>估计</b> despawn 还有多少 tick（已过期返回 0）。 */
        public long ticksLeft(long nowGameTime) {
            long left = DROPS_LIVE_TICKS - (nowGameTime - gameTime);
            return Math.max(0, left);
        }

        public long secondsLeft(long nowGameTime) {
            return ticksLeft(nowGameTime) / 20L;
        }

        public Death confirmedLost(String reason) {
            return new Death(seq, gameTime, wallClockMillis, deathAt, lostEntries,
                    State.CONFIRMED_LOST, reason, lostItems);
        }

        public Death recovered(String reason) {
            return new Death(seq, gameTime, wallClockMillis, deathAt, lostEntries,
                    State.RECOVERED, reason, lostItems);
        }
    }

    private static final Map<UUID, List<Death>> LEDGER = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SEQ = new ConcurrentHashMap<>();

    private RddDeathLedger() {}

    // ---- persistence contract (rdd-core stays IO-free; the host owns the disk) ----

    /**
     * 一个 UUID 的台账全量快照，供宿主落盘。纯数据，无 IO。
     *
     * <p>本类刻意<b>不</b>自己写文件：rdd-core 是纯 JVM 模块，不该出现 {@code Path}/JSON。
     * 宿主（plugins:rdd）负责加载 → {@link #restore}、变更后 → {@link #snapshot} 保存。
     *
     * @param nextSeq <b>下一次 record() 将要用的 seq</b>（不是"已用到的最大值"）。
     *                定义成前者是为了让 {@link #restore} 不必再猜偏移：
     *                {@code SEQ = nextSeq - 1} 就够了。
     */
    public record LedgerSnapshot(int nextSeq, List<Death> deaths) {
        public LedgerSnapshot {
            deaths = deaths == null ? List.of() : List.copyOf(deaths);
            nextSeq = Math.max(1, nextSeq);
        }
    }

    /**
     * 导出某同伴的台账（无记录时 {@code nextSeq=1}、空列表 —— 不是 null）。
     *
     * <p>空台账也必须能导出：宿主要在同伴首次出现时写出一个"空账本"文件，
     * 否则「有没有落过盘」和「本来就没死过」会混成同一个状态。
     */
    public static LedgerSnapshot snapshot(UUID companionId) {
        UUID id = companionId == null ? new UUID(0, 0) : companionId;
        // SEQ 存的是"已用到的最大值"，对外一律 +1 变成"下一个"。
        return new LedgerSnapshot(SEQ.getOrDefault(id, 0) + 1, all(id));
    }

    /**
     * 用快照覆盖某同伴的台账（宿主启动/世界载入时调）。
     *
     * <p><b>必须先 restore 再让死亡事件进来</b>：反过来（先 record 后 restore）会用旧账
     * 覆盖掉本次运行刚记的死亡，那比丢账更坏（假"没死过"）。宿主侧用 per-UUID
     * loaded guard 保证只在首次接入时 restore 一次。
     *
     * <p>{@code nextSeq} 取 {@code max(快照值, 现有最大 seq + 1)}：宁可 seq 跳号，
     * 也不允许下一条记录复用磁盘上已有的 seq（seq 是批次身份，撞号 = 判据串批）。
     * 内部 {@code SEQ} 存"已用到的最大值"，所以写入 {@code nextSeq - 1}。
     */
    public static void restore(UUID companionId, LedgerSnapshot snapshot) {
        UUID id = companionId == null ? new UUID(0, 0) : companionId;
        if (snapshot == null) return;
        List<Death> restored = snapshot.deaths().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(Death::seq))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        while (restored.size() > MAX_ENTRIES) {
            restored.remove(0);
        }
        List<Death> current = LEDGER.get(id);
        int nextSeq = snapshot.nextSeq();
        if (current != null && !current.isEmpty()) {
            int afterCurrent = current.stream().mapToInt(Death::seq).max().orElse(0) + 1;
            nextSeq = Math.max(nextSeq, afterCurrent);
        }
        LEDGER.put(id, restored);
        SEQ.put(id, Math.max(0, nextSeq - 1));
    }

    /**
     * 记一次死亡，返回新写入的那条。
     *
     * <p>{@code gameTime} 用 RddInstrumentation 的游戏时间（与 despawn 同口径）；
     * 传负数表示"读不到游戏时间"，此时本条不参与到期判定（{@code ticksLeft} 恒为满窗），
     * 免得用墙钟去猜一个和原版不同口径的时钟。
     */
    public static Death record(UUID companionId, long gameTime, long wallClockMillis,
                               String deathAt, int lostEntries) {
        return record(companionId, gameTime, wallClockMillis, deathAt, lostEntries, null);
    }

    /**
     * 记一次死亡，<b>并带上这一次真正掉了哪些、各多少</b>（F6 的批次身份来源）。
     *
     * <p>{@code lostItems} 必须是<b>死亡瞬间</b>的背包计数，而不是缓存：
     * 上一轮实机之所以"LOST 没新增"，就是因为读了已被清空的缓存。
     * null/空 = 无法逐项核对（该批次不会自动判回收，需宿主显式 confirmLost/confirmRecovered）。
     */
    public static Death record(UUID companionId, long gameTime, long wallClockMillis,
                               String deathAt, int lostEntries, Map<String, Integer> lostItems) {
        UUID id = companionId == null ? new UUID(0, 0) : companionId;
        int seq = SEQ.merge(id, 1, Integer::sum);
        Map<String, Integer> items = null;
        if (lostItems != null && !lostItems.isEmpty()) {
            items = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(lostItems));
        }
        Death d = new Death(seq, gameTime, wallClockMillis,
                deathAt == null ? "?" : deathAt, Math.max(0, lostEntries),
                State.UNVERIFIED, "", items);
        List<Death> list = LEDGER.computeIfAbsent(id, k -> new ArrayList<>());
        synchronized (list) {
            list.add(d);
            while (list.size() > MAX_ENTRIES) {
                list.remove(0);
            }
        }
        return d;
    }

    /**
     * 记下「这一次死亡的东西**确实**没了」的证据（2026-09-30 深审 R07）。
     *
     * <p>没有这个方法，系统只能靠时钟猜——而时钟猜不出来（原版只在区块被 tick 时给掉落物计龄）。
     * 宿主拿到<b>真实证据</b>时（世界扫描确认不在 / 已拾回 / 被火岩浆销毁 / 墓碑模组已收殓）调它，
     * 规划器才被允许说"永久丢失"。
     *
     * @param seq   {@link Death#seq()}；传负数 = 标记最近那一次
     * @param reason 证据来源（人话，会进事件与规划上下文）
     */
    public static boolean confirmLost(UUID companionId, int seq, String reason) {
        List<Death> list = LEDGER.get(companionId);
        if (list == null || list.isEmpty()) return false;
        synchronized (list) {
            Death target = null;
            for (Death d : list) {
                if (seq < 0 || d.seq() == seq) { target = d; break; }
            }
            if (target == null) return false;
            list.remove(target);
            list.add(target.confirmedLost(reason == null || reason.isBlank() ? "confirmed gone" : reason));
            return true;
        }
    }

    /**
     * 记下「这一次死亡的东西<b>已经捡回来了</b>」。
     *
     * <p>与 {@link #confirmLost} 分开是因为语义相反：一个是"没了"，
     * 一个是"还在而且已经拿回来了"——后者应该让规划器**去掉**这个回收目标。
     */
    public static boolean confirmRecovered(UUID companionId, int seq, String reason) {
        List<Death> list = LEDGER.get(companionId);
        if (list == null || list.isEmpty()) return false;
        synchronized (list) {
            Death target = null;
            for (Death d : list) {
                if (seq < 0 || d.seq() == seq) { target = d; break; }
            }
            if (target == null) return false;
            list.remove(target);
            list.add(target.recovered(reason == null || reason.isBlank() ? "picked up" : reason));
            return true;
        }
    }

    /** 已确认丢失的那些（真正"没了"的）。 */
    public static List<Death> confirmedLost(UUID companionId) {
        List<Death> out = new ArrayList<>();
        for (Death d : all(companionId)) {
            if (d.state() == State.CONFIRMED_LOST) out.add(d);
        }
        return List.copyOf(out);
    }

    /** 已确认捡回的那些。 */
    public static List<Death> recovered(UUID companionId) {
        List<Death> out = new ArrayList<>();
        for (Death d : all(companionId)) {
            if (d.state() == State.RECOVERED) out.add(d);
        }
        return List.copyOf(out);
    }

    /** 全部记录，最新在前。 */
    public static List<Death> all(UUID companionId) {
        List<Death> list = LEDGER.get(companionId);
        if (list == null) return List.of();
        synchronized (list) {
            List<Death> copy = new ArrayList<>(list);
            copy.sort(Comparator.comparingLong(Death::gameTime).reversed());
            return List.copyOf(copy);
        }
    }

    /** 掉落物<b>可能</b>还在地上的那些（按到期时刻从早到晚排：先捡最先过期的）。 */
    public static List<Death> recoverable(UUID companionId, long nowGameTime) {
        List<Death> out = new ArrayList<>();
        for (Death d : all(companionId)) {
            if (d.stillRecoverable(nowGameTime)) out.add(d);
        }
        out.sort(Comparator.comparingLong(Death::gameTime));
        return List.copyOf(out);
    }

    /**
     * 超过拾取窗口的（<b>不等于"没了"</b>，只是"按估计过期了"）。
     *
     * <p>深审 R07：这个方法名容易被读成"已丢失"，所以语义收紧为
     * "过期且未确认"。真正丢失的看 {@link #confirmedLost}。
     */
    public static List<Death> expired(UUID companionId, long nowGameTime) {
        List<Death> out = new ArrayList<>();
        for (Death d : all(companionId)) {
            if (d.state() == State.UNVERIFIED && !d.stillRecoverable(nowGameTime)) out.add(d);
        }
        return List.copyOf(out);
    }

    /** 最近一条；没有则 null。 */
    public static Death latest(UUID companionId) {
        List<Death> a = all(companionId);
        return a.isEmpty() ? null : a.get(0);
    }

    /**
     * 某一次死亡的<b>未闭合</b>记录（按 seq 精确取），没有则 null。
     *
     * <p>已判定 RECOVERED / CONFIRMED_LOST 的记录算闭合。闭合的批次**不允许**再被回收判据
     * 命中 —— 否则「上一批的物品回来了」会替「这一批」结账（串批，F6 的根因）。
     */
    public static Death openDeath(UUID companionId, int seq) {
        for (Death d : all(companionId)) {
            if (d.seq() == seq) {
                return d.state() == State.UNVERIFIED ? d : null;
            }
        }
        return null;
    }

    /**
     * 最新一条<b>未闭合</b>死亡记录；全部闭合时返回 null。回收判据只认它。
     *
     * <p>为什么要"只认最新未闭合"而不是"扫描任意一条"：连死两次时两个批次的
     * {@code lostItems} 完全不同（实机 19:22 丢 32 格 / 19:23 又丢 32 格）。
     * 判据若能匹配任意批次，就会出现"第二次死亡还没捡，第一次的掉落还在，
     * 于是判第二次已回收"这种假完成。
     */
    public static Death latestOpen(UUID companionId) {
        for (Death d : all(companionId)) {   // all() 已按 gameTime 倒序
            if (d.state() == State.UNVERIFIED) return d;
        }
        return null;
    }

    /**
     * 一次死亡批次的回收核对结果（纯函数，无 IO）。
     *
     * @param seq        该批次 seq；不匹配任何未闭合记录时 {@code matched=false}
     * @param outstanding 该批次里"当前背包里仍然没有"的条目数（>0 = 还没捡回来）
     * @param tracked    该批次可核对的条目数（lostItems 非空才有核对意义）
     * @param satisfied  是否达到"全部可核对条目都已回到背包"
     */
    public record BatchRecovery(int seq, boolean matched, int outstanding, int tracked, boolean satisfied) {
        public boolean anyBack() { return matched && tracked > 0 && outstanding < tracked; }

        /** 0..1 的回收比例（无可核对条目时 0，绝不假装 100%）。 */
        public double fraction() {
            return tracked <= 0 ? 0d : (double) (tracked - outstanding) / (double) tracked;
        }
    }

    /**
     * 核对某一批次的掉落是否真的捡回来了（按 {@code deathSeq} 关联，F6 的修法）。
     *
     * <p><b>旧判据错在哪</b>：宿主原来只问"背包里<b>任何一件</b>当初标 LOST 的东西是不是 &gt;0"，
     * 于是 33 格掉落里捡回 1 格就判整个死亡支线成功。更糟的是那一件可能根本不是这一批丢的
     * —— {@code AssetHistory} 是长期累积表，旧批次的物品回来会替新批次结账。
     *
     * <p><b>现在的判据</b>：逐项按批次自己的 {@code lostItems} 核对，
     * {@code min(当前持有, 当次丢失数)} 累加；只有<b>全部可核对条目都回到背包</b>才算满足。
     * 部分找回由宿主发 progress 事件，不在这里下成功结论。
     *
     * <p><b>为什么不是比例法定数</b>（如 80%）：那是个没被验证过的策略常数。
     * 本轮只把"任意一件"换成"本批次逐项核对"；比例策略要单独一批、有实机数据再定。
     *
     * <p>{@code lostItems} 为空的老批次（本次改动前写下的）→ {@code tracked=0}、
     * {@code satisfied=false}：宁可要求宿主显式 {@link #confirmLost}，也不假装已核对。
     *
     * @param counts 当前真实背包计数（宿主提供）
     */
    public static BatchRecovery checkBatchRecovery(UUID companionId, int seq, Map<String, Integer> counts) {
        Death open = openDeath(companionId, seq);
        if (open == null || open.lostItems() == null || open.lostItems().isEmpty()) {
            return new BatchRecovery(seq, open != null, 0, 0, false);
        }
        Map<String, Integer> current = counts == null ? Map.of() : counts;
        int outstanding = 0;
        int tracked = 0;
        for (Map.Entry<String, Integer> e : open.lostItems().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            int lost = e.getValue() == null ? 0 : Math.max(0, e.getValue());
            if (lost <= 0) continue;          // 当时就没这玩意，不算待回收
            tracked++;
            int back = Math.min(current.getOrDefault(e.getKey(), 0), lost);
            if (back < lost) outstanding++;
        }
        return new BatchRecovery(seq, true, outstanding, tracked, tracked > 0 && outstanding == 0);
    }

    /**
     * 重复死亡判据：短时间 + 小半径内<b>上一次</b>又死一次（实机 #3→#4 只隔 62s、坐标差 1 格）。
     *
     * <p>纯函数。宿主用它决定死亡支线的<b>顺序</b>（先撤离/补给再回收），
     * 不改 5 分钟掉落窗口本身。
     *
     * <p>⚠️ <b>必须排除本次死亡自己</b>（2026-09-30 实机抓到的真缺陷）：
     * 宿主是「先 record 再问判据」，所以刚写下的那条记录就在台账里，
     * 而它与自己的距离是 0、时间差是 0 —— 会被自己判成"重复死亡"。
     * 实机症状：只杀一次就发出 {@code repeat_death_nearby}，
     * {@code previousDeathSeq} 等于 {@code nowAt}、{@code secondsSincePrevious=0}。
     * 于是每一次死亡都会被误当成"同一点连死"，恢复顺序永远走保守分支。
     * 传 {@code excludeSeq = 本次死亡的 seq} 即可排除；传负数 = 不排除（测试用）。
     *
     * @param atX/atY/atZ 本次死亡坐标（{@link #deathAt()} 只是一段文本，靠不住，这里要真坐标）
     * @param excludeSeq 本次死亡自己的 seq，必须排除
     * @return 命中的上一次死亡记录；不构成"重复"返回 null
     */
    public static Death repeatDeathWithin(UUID companionId, long nowGameTime,
                                          int atX, int atY, int atZ,
                                          long windowTicks, double radiusBlocks,
                                          int excludeSeq) {
        for (Death d : all(companionId)) {
            if (excludeSeq >= 0 && d.seq() == excludeSeq) continue;   // 不跟自己比
            if (nowGameTime - d.gameTime() > windowTicks) continue;
            if (isNear(d.deathAt(), atX, atY, atZ, radiusBlocks)) return d;
        }
        return null;
    }

    /** 不排除任何记录的版本（只给单测与"事后统计"用；生产路径请用带 excludeSeq 的重载）。 */
    public static Death repeatDeathWithin(UUID companionId, long nowGameTime,
                                          int atX, int atY, int atZ,
                                          long windowTicks, double radiusBlocks) {
        return repeatDeathWithin(companionId, nowGameTime, atX, atY, atZ, windowTicks, radiusBlocks, -1);
    }

    /** {@link #deathAt()} 是 {@code "x, y, z"} 短文本；解析不了就当"距离未知"→ false。 */
    private static boolean isNear(String deathAt, int x, int y, int z, double radius) {
        if (deathAt == null || radius <= 0) return false;
        String[] parts = deathAt.split("[,\\s]+");
        if (parts.length != 3) return false;
        try {
            double dx = Double.parseDouble(parts[0].trim()) - x;
            double dy = Double.parseDouble(parts[1].trim()) - y;
            double dz = Double.parseDouble(parts[2].trim()) - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius;
        } catch (NumberFormatException notCoordinates) {
            return false;
        }
    }

    /** 死亡次数（台账内计数）。 */
    public static int deathsRecorded(UUID companionId) {
        return all(companionId).size();
    }

    /**
     * 渲染成一句话交给规划器/执行层。
     *
     * <p><b>2026-09-30 深审 R07（codex 判定必须进第一批红线）：区分「估计」与「确认」。</b>
     *
     * <p>旧实现只要"距死亡超过 5 分钟"就断言 {@code ALL ... have already despawned}、
     * {@code treat those items as permanently gone}。但原版掉落物的年龄只在<b>它自己所在的
     * 区块被 tick</b> 时增长：玩家跑远、区块卸载、世界时间照样走，掉落物其实还在原处。
     * 按时钟断言"永久丢失"会让士兵**白跑一趟甚至放弃本来还在的东西**，
     * 而这正踩在死亡恢复红线上。
     *
     * <p>现在分三档：
     * <ul>
     *   <li><b>还在窗口内</b> → 「去拿，还剩约 N 秒」（估计，可信度最高）</li>
     *   <li><b>超窗但未确认</b> → 「可能已消失，也可能只是那边没加载；
     *       顺路经过就捡，别专门跑」——<b>不再断言丢失</b></li>
     *   <li><b>确认丢失</b> → 只有拿到真实证据（世界扫描找不到 / 已拾回 / 被销毁）才这么说</li>
     * </ul>
     */
    public static String render(UUID companionId, long nowGameTime) {
        List<Death> a = all(companionId);
        if (a.isEmpty()) {
            return "no death recorded — nothing to recover";
        }
        List<Death> live = recoverable(companionId, nowGameTime);
        StringBuilder sb = new StringBuilder();
        if (live.isEmpty()) {
            List<Death> confirmed = confirmedLost(companionId);
            if (confirmed.isEmpty()) {
                sb.append(a.size()).append(" death record(s), all past the ~").append(DROPS_LIVE_TICKS / 20)
                  .append("s pickup window. That does NOT prove the items are gone: vanilla only ages")
                  .append(" drops in chunks that are still loaded, so they may be sitting there")
                  .append(" untouched while the area is unloaded. If you happen to pass that way,")
                  .append(" look for them; do not make a special trip, and do not count them as lost yet.");
                return sb.toString();
            }
            sb.append(confirmed.size()).append(" of ").append(a.size())
              .append(" death drop(s) are CONFIRMED gone (scanned and absent, picked up, or destroyed).")
              .append(" Treat only these as permanently gone and plan without them.");
            return sb.toString();
        }
        sb.append(live.size()).append(" death drop(s) still within the pickup window (of ")
          .append(a.size()).append(" recorded):");
        for (Death d : live) {
            sb.append("\n  #").append(d.seq()).append(' ').append(d.deathAt())
              .append(" — ").append(d.lostEntries()).append(" items, est. despawns in ")
              .append(d.secondsLeft(nowGameTime)).append('s');
        }
        if (live.size() > 1) {
            sb.append("\nGo to the NEAREST one first — the later ones have more time left.");
        }
        return sb.toString();
    }
    /** 清掉某同伴的台账（任务重置/世界重载时用，免得把上一局的死算进这一局）。 */
    public static void clear(UUID companionId) {
        LEDGER.remove(companionId);
        SEQ.remove(companionId);
    }

    public static void clearAll() {
        LEDGER.clear();
        SEQ.clear();
    }
}
