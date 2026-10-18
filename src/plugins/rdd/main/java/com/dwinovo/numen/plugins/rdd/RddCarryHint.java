package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.ai.AiLog;
import com.dwinovo.numen.api.CompanionAlerts;
import com.dwinovo.numen.api.carrier.CarrierAlarms;
import com.dwinovo.numen.api.carrier.CarrierChain;
import com.dwinovo.numen.api.carrier.CarrierRuleStore;
import com.dwinovo.numen.api.carrier.CarrierRules;
import com.dwinovo.numen.api.carrier.ItemSemantics;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 携带器 → 执行 AI 上下文的<b>提醒</b>（{@code 38} v3.6 B24）。
 *
 * <p><b>为什么有这一类</b>：第 1 批与第 2 批前半段全是脑内改动 —— 携带器算出一个
 * {@code List<String>} 然后<b>没人看</b>，所以游戏里看不出任何变化。
 * 本类把那个清单<b>注入执行 AI 读得到的上下文</b>。
 *
 * <p><b>★ 形态：提醒，不是指令</b>。依据 memory
 * {@code method/commentary-is-intent-visible-not-authority}：
 * <b>公开叙述 ≠ 执行授权，两者必须物理分离</b>。
 * 所以措辞一律「建议 / 可以考虑」，<b>禁止</b>「必须 / 立刻」；
 * 且本类<b>只产出文本，不改任何判定</b>，也不注册任何工具（RL-1/RL-9 不受影响）。
 *
 * <p><b>★ 照抄既有缓存模式</b>（{@code RddPlugin.LAST_INVENTORY} / {@code LAST_INVENTORY_AT}）：
 * <b>只在服务端主线程 refresh（读世界），上下文构建只 current()（读缓存）</b>。
 * 原因：{@code contributeState} 在构建上下文时被调用，<b>不保证在主线程</b>；
 * 而 {@code getHealth} / {@code getInventory} / {@code getEntities} 离开主线程不安全。
 *
 * <p><b>★ 不得成为新的目标漂移源</b>：{@code note} 里明写「不要为了携带而中断当前目标」。
 * 携带 AI 跑去拿护甲而放弃当前任务，是注入型设计最典型的失效。
 *
 * <p><b>异常一律不注入</b>：本类全程 try/catch —— {@code renderStateContext} 在每次
 * 上下文构建时被调用，<b>不能被携带器拖崩</b>。
 */
final class RddCarryHint {

    private RddCarryHint() {
    }

    private static final Map<UUID, String> CARRY_HINT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CARRY_HINT_AT = new ConcurrentHashMap<>();

    /** 上次已发布的闹钟状态（rule → prio）。只在边沿（新增/解除/升级）发事件，平稳期不刷。 */
    private static final Map<UUID, Map<String, String>> ALARM_STATE = new ConcurrentHashMap<>();

    /** 主动唤醒限频（E2.2）：同一条闹钟在冷却内不重复叫，防 P0/P1 震荡刷屏。 */
    private static final Map<UUID, Map<String, Long>> WAKE_AT = new ConcurrentHashMap<>();
    private static final long WAKE_COOLDOWN_MS = 30_000L;

    /** 刷新间隔：快照级变化不需要每 tick（比这更频繁只是白读世界）。 */
    private static final long REFRESH_MS = 2000L;

    /** 携带清单**上限**：防上下文变长。只给缺项时通常 0~3 项。 */
    private static final int MAX_ITEMS = 4;

    /** 避让半径：只关心「身边有没有威胁」，不做全图扫描。 */
    private static final double NEARBY_RADIUS = 16.0D;

    /** 读缓存。**永不读世界** —— 上下文构建不该有副作用。 */
    static String current(UUID companionId) {
        return companionId == null ? "" : CARRY_HINT.getOrDefault(companionId, "");
    }

    /** 全局清理（与 {@code LAST_INVENTORY.clear()} 同处调用）。 */
    static void invalidateAll() {
        CARRY_HINT.clear();
        CARRY_HINT_AT.clear();
        ALARM_STATE.clear();
        WAKE_AT.clear();
    }

    /** 拆卸同伴时必须一起清（与 {@code RddPlugin} 清 {@code LAST_INVENTORY} 同处理，防泄漏）。 */
    static void invalidate(UUID companionId) {
        if (companionId == null) {
            return;
        }
        CARRY_HINT.remove(companionId);
        CARRY_HINT_AT.remove(companionId);
        ALARM_STATE.remove(companionId);
        WAKE_AT.remove(companionId);
    }

    /**
     * 在服务端主线程刷新缓存。
     *
     * <p>非主线程 / 同伴不在世界 / 任何异常 → <b>清掉过期缓存</b>并返回，
     * <b>不抛、不阻塞</b>。
     */
    static void refresh(MinecraftServer server, UUID companionId) {
        if (server == null || companionId == null) {
            return;
        }
        Long at = CARRY_HINT_AT.get(companionId);
        long now = System.currentTimeMillis();
        if (at != null && now - at < REFRESH_MS) {
            return;
        }
        // ⚠️ 2026-10-01 实机抓到（0 命中 <carry> 的真凶）：**时间戳必须先判断线程、再写**。
        //    原来我在判 isSameThread 之前就 CARRY_HINT_AT.put(...)，于是
        //    「在非主线程被调」→ 写完时间戳就 return → 缓存永远填不上，
        //    而 findByUuid 根本没执行，而且**不报错**。
        //    这是我今天第三次犯「兜底加到把功能关掉」—— 顺序错了，兜底就变成永久 no-op。
        if (!server.isSameThread()) {
            // 非主线程：**不写时间戳**（否则下次在主线程也会被节流窗口挡住），只丢掉过期的
            if (at != null && now - at > REFRESH_MS * 4) {
                CARRY_HINT.remove(companionId);
                CARRY_HINT_AT.remove(companionId);
            }
            AiLog.LOG.debug("[rdd] carry refresh 跳过：不在服务端主线程 {}", companionId);
            return;
        }
        CARRY_HINT_AT.put(companionId, now);
        try {
            NumenPlayer body = NumenPlayer.findByUuid(server, companionId);
            if (body == null || body.serverLevel() == null) {
                CARRY_HINT.remove(companionId);
                CARRY_HINT_AT.remove(companionId);
                ALARM_STATE.remove(companionId);
                AiLog.LOG.info("[rdd] carry refresh：同伴不在世界里（body={}），本轮不注入 <carry> {}",
                        body == null ? "null" : "level-null", companionId);
                return;
            }
            Rendered rendered = render(body);
            publishAlarmEdges(body, rendered.alarms());
            String hint = rendered.xml();
            if (hint.isEmpty()) {
                CARRY_HINT.remove(companionId);
                AiLog.LOG.info("[rdd] carry refresh：快照或分级结果为空（无 <carry>/<alarms> 可注入）{}", companionId);
            } else {
                CARRY_HINT.put(companionId, hint);
                AiLog.LOG.info("[rdd] carry refresh：已注入提醒 {} 字符 {}", hint.length(), companionId);
            }
        } catch (Throwable t) {
            // 携带器是「锦上添花」，任何问题都不许影响主链路。
            // ⚠️ 这里也**不写时间戳** —— 失败了要让下次还能重试，不能被节流锁死。
            CARRY_HINT.remove(companionId);
            CARRY_HINT_AT.remove(companionId);
            AiLog.LOG.info("[rdd] carry refresh 异常（本轮不注入）: {}", t.toString());
        }
    }

    /** 一次渲染的产物：注入文本 + 本次命中的独立闹钟（供边沿埋点）。 */
    private record Rendered(String xml, List<CarrierAlarms.Hit> alarms) {
    }

    /**
     * 把携带器结论渲染成 {@code <carry>…</carry>} 与独立闹钟 {@code <alarms>…</alarms>}。
     * 快照缺失 / 两条通道都空 → 返回空串（B21：不写空标签）。
     *
     * <p><b>E2</b>：{@code <carry>} 是旧串行链（语义不动，短路照旧）；{@code <alarms>} 是
     * 独立闹钟（{@link CarrierAlarms}，互不短路）。两条通道各自独立判空 ——
     * 旧链短路不再压掉闹钟，闹钟也不改写旧链的 basis。
     */
    private static Rendered render(NumenPlayer body) {
        String snapshot = snapshot(body);
        if (snapshot.isEmpty()) {
            return new Rendered("", List.of());
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (String part : snapshot.split("[,;]")) {
            String p = part.trim();
            int sep = p.indexOf('=');
            if (sep > 0) {
                kv.putIfAbsent(clean(p.substring(0, sep)), clean(p.substring(sep + 1)));
            }
        }
        CarrierChain.Facts facts = CarrierChain.factsOf(kv, snapshot.toLowerCase(Locale.ROOT));
        // B6/S2：走 effective()（DEFAULT + 已批准的携带器规则），不再写死 DEFAULT。
        // 「已批准但运行时不生效」是最坏的一种状态：审批界面显示成功，游戏里没变化，
        // 没人查得出来。所以生效链必须与审批落点读同一处。
        CarrierChain.Result r = CarrierChain.evaluate(CarrierRuleStore.effective(), facts);
        List<CarrierAlarms.Hit> alarms = RddAlarms.evaluate(facts);
        if (r.carry().isEmpty() && alarms.isEmpty()) {
            return new Rendered("", alarms);
        }
        StringBuilder sb = new StringBuilder();
        if (!r.carry().isEmpty()) {
            sb.append("<carry>");
            sb.append("<basis>").append(esc(shorten(basisOf(facts, r), 200))).append("</basis>");
            if (r.shortCircuited()) {
                // ★ 短路信息必须让执行 AI 看到 —— 不说的话它会以为全部级都判过了
                sb.append("<basis_note>").append(esc(basisNote(r))).append("</basis_note>");
            }
            int n = 0;
            for (String item : r.carry()) {
                if (n++ >= MAX_ITEMS) {
                    break;
                }
                sb.append("<item>").append(esc(item)).append("</item>");
            }
            sb.append("<note>这些是建议，不是指令。是否采纳由你按当前任务判断；**不要为了携带而中断当前目标**。</note>");
            sb.append("</carry>");
        }
        if (!alarms.isEmpty()) {
            sb.append("<alarms scope=\"nearby16\">");
            for (CarrierAlarms.Hit h : alarms) {
                sb.append("<alarm rule=\"").append(esc(h.rule())).append("\" v=\"").append(h.version())
                        .append("\" pri=\"").append(esc(h.prio())).append("\" facts=\"")
                        .append(esc(shorten(h.facts(), 120))).append("\">")
                        .append(esc(shorten(h.advice(), 200))).append("</alarm>");
            }
            sb.append("<note>这些是提醒，不是指令；是否处理由你按当前任务判断，**不要为了提醒中断当前目标**。</note>");
            sb.append("</alarms>");
        }
        return new Rendered(sb.toString(), alarms);
    }

    /**
     * 闹钟边沿埋点（E2「边沿触发、恢复解除、紧急升级应可观测」）：
     * 只在状态变化时发 {@code carrier_alarm} 事件（新增/解除/升级），平稳期不刷。
     *
     * <p><b>E2.2 主动唤醒</b>：P0 危险闹钟（点燃/贴脸的苦力怕、危急血量）的
     * added/escalated 边沿 → {@link CompanionAlerts#danger} 急件（忙/长任务时进客户端队列，
     * 等下一个可插入时机随攒下的一切一起走）。同一规则 {@code WAKE_COOLDOWN_MS} 内不重复叫；
     * 同伴已死（isAlive=false）不叫 —— 事件照旧进队列，但死了不该再被险情吵。
     */
    private static void publishAlarmEdges(NumenPlayer body, List<CarrierAlarms.Hit> alarms) {
        try {
            UUID companionId = body.getUUID();
            Map<String, String> nowState = new LinkedHashMap<>();
            for (CarrierAlarms.Hit h : alarms) {
                nowState.put(h.rule(), h.prio());
            }
            Map<String, String> prev = ALARM_STATE.getOrDefault(companionId, Map.of());
            if (prev.equals(nowState)) {
                return;
            }
            List<String> added = new ArrayList<>();
            List<String> escalated = new ArrayList<>();
            for (Map.Entry<String, String> e : nowState.entrySet()) {
                String old = prev.get(e.getKey());
                if (old == null) {
                    added.add(e.getKey() + ":" + e.getValue());
                } else if (!old.equals(e.getValue())) {
                    escalated.add(e.getKey() + ":" + old + "->" + e.getValue());
                }
            }
            List<String> removed = new ArrayList<>();
            for (String k : prev.keySet()) {
                if (!nowState.containsKey(k)) {
                    removed.add(k);
                }
            }
            ALARM_STATE.put(companionId, nowState);
            // E2.2：危险边沿 → 主动唤醒（urgent 事件）。冷却防震荡，死亡不叫。
            int woke = 0;
            if (body.isAlive()) {
                long nowMs = System.currentTimeMillis();
                Map<String, Long> wakeAt = WAKE_AT.computeIfAbsent(companionId, k -> new LinkedHashMap<>());
                for (CarrierAlarms.Hit h : alarms) {
                    String old = prev.get(h.rule());
                    String edge = old == null ? "added" : (old.equals(h.prio()) ? "active" : "escalated");
                    if (!CarrierAlarms.wakeWorthy(edge, h)) {
                        continue;
                    }
                    Long last = wakeAt.get(h.rule());
                    if (last != null && nowMs - last < WAKE_COOLDOWN_MS) {
                        continue;
                    }
                    wakeAt.put(h.rule(), nowMs);
                    CompanionAlerts.danger(body, h.rule(), h.prio(), h.facts(), h.advice());
                    woke++;
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companionId", companionId.toString());
            data.put("added", String.join(",", added));
            data.put("escalated", String.join(",", escalated));
            data.put("removed", String.join(",", removed));
            data.put("active", String.join(",", nowState.keySet()));
            if (woke > 0) {
                data.put("woke", woke);
            }
            RddMonitor.publish("carrier_alarm", data);
        } catch (RuntimeException ignored) {
            // 埋点是锦上添花，不许影响主链路
        }
    }

    private static String basisOf(CarrierChain.Facts f, CarrierChain.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("指向=").append(f.hostileNearby() ? "HOSTILE_NEARBY" : (f.passiveNearby() ? "PASSIVE_ONLY" : "NONE"));
        sb.append(" 护甲=").append(f.hasRealArmor() ? "有" : "无");
        sb.append(" 武器=").append(f.hasRealWeapon() ? "有" : "无");
        sb.append(" 血量=").append(f.hpBand()).append(f.hasHp() ? ("(" + f.hp() + ")") : "");
        return sb.toString();
    }

    private static String basisNote(CarrierChain.Result r) {
        int notEvaluated = Math.max(0, CarrierRuleStore.effective().size() - r.stoppedAt() - 1);
        return "第 " + (r.stoppedAt() + 1) + " 级不成立，后续 " + notEvaluated + " 项未求值";
    }

    /**
     * 造一份与 {@code plugins/learner} 的 {@code EnvSnapshot} <b>同格式</b>的快照。
     *
     * <p>格式必须能被 {@code CarrierChain} 认出来：{@code key=value} 对、逗号分隔、<b>不能按空格切</b>。
     */
    private static String snapshot(NumenPlayer body) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("hp=").append((int) Math.ceil(body.getHealth()))
              .append('/').append((int) Math.ceil(body.getMaxHealth()));
            // 2026-10-08 生命不满提醒：hp=cur/max 之外单给一个布尔，供 hp_topup 闹钟读
            //（Facts 只暴露当前血量、拿不到上限，所以这里显式算）。
            try {
                sb.append(", hp_full=").append(body.getHealth() >= body.getMaxHealth() ? "1" : "0");
            } catch (RuntimeException ignored) {
                // 读不到就不写：缺失 = 不触发
            }
            // ★ food = **饱食度**（0-20），不是「背包里有没有吃的」。
            //   两者是两件事：饱食度高也可能一颗食物都没有（刚吃完），
            //   饱食度低也可能背包塞满面包。想让规则按「有没有吃的」判，
            //   用 food_items（下面那个），别把 food 当库存读。
            sb.append(", food=").append(body.getFoodData().getFoodLevel());
            // ★ 2026-10-06 补：背包（含副手）里**可食用**物品的总个数。
            //   取不到写 -1（= 未知），**不写 0** —— 0 是「真的没有」这个真值，
            //   把「没读到」写成 0 会让规则在「其实有食物」时也触发（DL-4：UNKNOWN ≠ 0）。
            sb.append(", food_items=").append(edibleCount(body));

            String chest = itemPath(body.getItemBySlot(EquipmentSlot.CHEST));
            String legs = itemPath(body.getItemBySlot(EquipmentSlot.LEGS));
            String head = itemPath(body.getItemBySlot(EquipmentSlot.HEAD));
            String boots = itemPath(body.getItemBySlot(EquipmentSlot.FEET));
            if (!chest.isEmpty()) {
                sb.append(", armor=").append(chest).append(", chestplate=").append(chest);
            }
            if (!legs.isEmpty()) {
                sb.append(", leggings=").append(legs);
            }
            if (!head.isEmpty()) {
                sb.append(", helmet=").append(head);
            }
            if (!boots.isEmpty()) {
                sb.append(", boots=").append(boots);
            }
            if (chest.isEmpty() && legs.isEmpty() && head.isEmpty() && boots.isEmpty()) {
                sb.append(", armor=none");
            }

            String main = itemPath(body.getMainHandItem());
            if (main.isEmpty() || "air".equals(main)) {
                sb.append(", weapon=none");
            } else {
                sb.append(", weapon=").append(main);
            }
            // ★ 2026-10-08 换装提醒：背包里有比身上更好的护甲/武器没穿。
            //   值里**不能用逗号/分号/加号/冒号** —— snapshot 按 [,;] 切键值对，且 render 侧
            //   clean() 只保留 [A-Za-z0-9_./-]。所以槽位之间用 - 连、槽位与物品名之间用 . 连。
            String[] gap = gearGap(body);
            if (gap != null) {
                sb.append(", gear_gap=").append(gap[0]);
                sb.append(", gear_best=").append(gap[1]);
            }
            // ★ E2：床（睡觉可行性）。按 BedItem 语义判（模组床只要继承 BedItem 就算），
            //   读不到写 -1（未知）**不写 0**。注意：非主世界闹钟不看这个键（见 night 的采样保证）。
            sb.append(", bed=").append(bedCount(body));

            List<String> names = new ArrayList<>();
            boolean hostile = false;
            int nearestCreeper = -1;
            boolean creeperIgnited = false;
            try {
                for (Entity e : body.serverLevel().getEntities(body,
                        body.getBoundingBox().inflate(NEARBY_RADIUS), x -> true)) {
                    if (e == null || e == body) {
                        continue;
                    }
                    if (e instanceof Enemy) {
                        hostile = true;
                    }
                    // ★ E2：苦力怕（闹钟数据）。距离 + 点燃态（getSwellDir()>0 = 引信已响）。
                    if (e instanceof Creeper c) {
                        int d = (int) Math.round(Math.sqrt(body.distanceToSqr(c)));
                        if (nearestCreeper < 0 || d < nearestCreeper) {
                            nearestCreeper = d;
                        }
                        if (c.isIgnited() || c.getSwellDir() > 0) {
                            creeperIgnited = true;
                        }
                    }
                    if (names.size() < 6) {
                        String n = safeName(e);
                        if (!n.isEmpty()) {
                            names.add(n);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // 世界卸载 / 区块未加载：缺这一项不该让整份快照失败
            }
            if (!names.isEmpty()) {
                sb.append(", nearby=").append(String.join("+", names));
            }
            sb.append(", hostile=").append(hostile);
            // ★ E2：只有范围内有苦力怕才写这两个键；没有 = 键缺失 = 闹钟不触发（脱离即恢复）。
            if (nearestCreeper >= 0) {
                sb.append(", creeper=").append(nearestCreeper)
                  .append(", ignited=").append(creeperIgnited);
            }
            sb.append(", dim=").append(body.serverLevel().dimension().location().toString());
            // ★ E2：时间/夜晚。night 只在有昼夜循环的维度写（下界/末地不写 →
            //   闹钟不会错误建议睡觉；这是「非主世界不错误睡觉」的采样端保证）。
            try {
                var level = body.serverLevel();
                sb.append(", time=").append((int) (level.getDayTime() % 24000L));
                if (!level.dimensionType().hasFixedTime()) {
                    sb.append(", night=").append(level.isNight() ? "1" : "0");
                }
            } catch (RuntimeException ignored) {
                // 时间拿不到就不写，不编造
            }
        } catch (RuntimeException e) {
            return "";
        }
        return sb.toString();
    }

    /**
     * 背包（含副手）里<b>可食用</b>物品的总个数。
     *
     * <p><b>为什么按食物数据组件判而不是按名字</b>：这是模组整合包
     * （暮色森林等），食物大多不叫 {@code *_food} / {@code bread}。
     * 按名字猜会漏掉一整片模组食物，而漏掉的后果是「系统说没有食物，其实有一背包」——
     * 正是 DL-4 要禁的那类假事实。{@code DataComponents.FOOD} 走的是物品自己注册的
     * {@code FoodProperties}，模组食物只要正常注册就一并覆盖。
     *
     * <p>数的是<b>个数</b>（{@code getCount()} 累加），不是槽位数 ——
     * 「还剩 3 个面包」和「还剩 1 格面包」对「有没有得吃」是两回事。
     *
     * <p>范围：{@code getContainerSize()} 覆盖快捷栏 + 主背包 + 盔甲 + <b>副手</b>
     * （见 {@code PlayerInv} 的注释），所以副手里的食物也算得到。
     *
     * @return 可食用物品总数；读世界失败返回 <b>-1</b>（未知，不是 0）
     */
    private static int edibleCount(NumenPlayer body) {
        try {
            var inv = body.getInventory();
            int n = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && st.get(DataComponents.FOOD) != null) {
                    n += st.getCount();
                }
            }
            return n;
        } catch (RuntimeException e) {
            // 世界卸载 / 区块未加载：缺这一项不该让整份快照失败，但也**不许**写成 0
            return -1;
        }
    }

    /**
     * 背包里<b>床</b>的总个数（E2 夜晚闹钟的「有床可睡」判据）。
     *
     * <p>按 {@code BedItem} 判（模组床只要继承它就一并覆盖）；数个数不数槽位。
     *
     * @return 床总数；读世界失败返回 <b>-1</b>（未知，不是 0）
     */
    private static int bedCount(NumenPlayer body) {
        try {
            var inv = body.getInventory();
            int n = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && st.getItem() instanceof BedItem) {
                    n += st.getCount();
                }
            }
            return n;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String itemPath(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 背包里有没有比身上更好的护甲/武器（换装提醒的数据源）。
     *
     * <p><b>判定口径（第一版，进游戏后再核）</b>：
     * <ul>
     *   <li><b>槽位/兵种识别按物品名后缀</b>：{@code _helmet/_cap/_hat} → 头、{@code _chestplate/_tunic} → 胸、
     *       {@code _leggings/_pants} → 腿、{@code _boots} → 脚；武器 {@code _sword/_axe/_trident/_bow}。
     *       暮色森林等整合包的装备基本都按这套命名，按名判比反射 {@code ArmorItem} 更稳、且不挑模组。</li>
     *   <li><b>档位用 {@code maxDamage} 代理</b>：同一槽里挑耐久上限最高的背包件与身上件比。这是"档位"的
     *       粗略代理（木&lt;石&lt;铁&lt;钻…大体单调），<b>不是精确护甲值/攻击力</b> —— 先把"明显有更好的没穿"
     *       这件事说出来，精确数值后续接属性读取再补。</li>
     * </ul>
     *
     * @return {@code [gapSlots, bestItems]}（用 {@code +} 连接，见 snapshot 的切分规则）；无差距返回 {@code null}
     */
    private static String[] gearGap(NumenPlayer body) {
        try {
            var inv = body.getInventory();
            String[] slots = {"head", "chest", "legs", "feet"};
            EquipmentSlot[] es = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            List<String> gaps = new ArrayList<>();
            List<String> best = new ArrayList<>();
            for (int i = 0; i < slots.length; i++) {
                int wornTier = tier(body.getItemBySlot(es[i]));
                int bestTier = wornTier;
                String bestPath = null;
                for (int k = 0; k < inv.getContainerSize(); k++) {
                    ItemStack st = inv.getItem(k);
                    String p = itemPath(st);
                    if (p.isEmpty() || !slots[i].equals(armorSlotOf(p))) {
                        continue;
                    }
                    int t = tier(st);
                    if (t > bestTier) {
                        bestTier = t;
                        bestPath = p;
                    }
                }
                if (bestPath != null) {
                    gaps.add(slots[i]);
                    best.add(slots[i] + "." + bestPath);
                }
            }
            // 武器：手里不是武器时基准 0（等于"没拿像样的武器"，背包有就提醒）
            String wornWeapon = itemPath(body.getMainHandItem());
            int wornWTier = isWeaponPath(wornWeapon) ? tier(body.getMainHandItem()) : 0;
            int bestWTier = wornWTier;
            String bestWPath = null;
            for (int k = 0; k < inv.getContainerSize(); k++) {
                ItemStack st = inv.getItem(k);
                String p = itemPath(st);
                if (p.isEmpty() || !isWeaponPath(p)) {
                    continue;
                }
                int t = tier(st);
                if (t > bestWTier) {
                    bestWTier = t;
                    bestWPath = p;
                }
            }
            if (bestWPath != null) {
                gaps.add("mainhand");
                best.add("mainhand." + bestWPath);
            }
            if (gaps.isEmpty()) {
                return null;
            }
            return new String[]{String.join("-", gaps), String.join("-", best)};
        } catch (RuntimeException e) {
            // 读世界失败：不产 fact（缺失 = 不触发），绝不拿默认值猜
            return null;
        }
    }

    /** 档位代理：物品的耐久上限；空/读不到 = 0。 */
    private static int tier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        try {
            return stack.getMaxDamage();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 物品名后缀 → 护甲槽；不是护甲返回 null。 */
    private static String armorSlotOf(String path) {
        if (path.endsWith("_helmet") || path.endsWith("_cap") || path.endsWith("_hat") || path.endsWith("_hood")) {
            return "head";
        }
        if (path.endsWith("_chestplate") || path.endsWith("_tunic") || path.endsWith("_chest")) {
            return "chest";
        }
        if (path.endsWith("_leggings") || path.endsWith("_pants") || path.endsWith("_legs")) {
            return "legs";
        }
        if (path.endsWith("_boots")) {
            return "feet";
        }
        return null;
    }

    /** 物品名后缀 → 是不是武器。 */
    private static boolean isWeaponPath(String path) {
        return path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_trident")
                || path.endsWith("_bow") || path.endsWith("_mace");
    }

    private static String safeName(Entity e) {
        try {
            return e.getName().getString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_+]", "");
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("[^A-Za-z0-9_./-]", "").toLowerCase(Locale.ROOT);
    }

    private static String shorten(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
