package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 经验的 JSONL 持久化（一行一条 {@link ExperienceEntry}）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>按稳定键去重</b>：同 {@code id} 的经验只合并，不无限追加——防止旧 DD 18K 条
 *       “同失败刷屏”问题复发。</li>
 *   <li><b>原子落盘</b>：每次变更写临时文件 + {@code ATOMIC_MOVE} 替换，不半写坏文件。</li>
 *   <li><b>best-effort</b>：IO 失败只记日志、保留内存态，绝不让经验写入拖垮宿主。</li>
 *   <li><b>线程安全</b>：本类所有变更方法 {@code synchronized}；内存镜像 + 全量重写，
 *       对经验这种小数据量是正确的换复杂度。</li>
 * </ul>
 *
 * <p>经验何时升级成熟度（详见 {@link #recordEvidence}）：真实成功累计证据；
 * 失败只加反例、最多把 OBSERVED 推到 ATTEMPTED，不降级已确认经验。
 */
public final class ExperienceStore {

    private static final Logger LOG = LoggerFactory.getLogger(ExperienceStore.class);

    /** 多少次真实成功从 VERIFIED 升级到 GENERALIZED。 */
    public static final int GENERALIZED_THRESHOLD = 3;
    /** 反例最多保留几条，防列表无限膨胀。 */
    public static final int MAX_COUNTEREXAMPLES = 5;

    /**
     * ★ E8：<b>连续</b>失败多少次才降一级成熟度。
     *
     * <p><b>⚠️ 这个 3 未经实测，是按 {@link #GENERALIZED_THRESHOLD} 对称取的，不是量出来的。</b>
     * 仓里没有任何「几次失败该降级」的观测数据（live 实例 22 条里
     * {@code verified=1 / generalized=0}，失败样本几乎为零）⇒ 取 3 只是为了
     * 「不太容易抖、也不太难降」的中间值。</p>
     *
     * <p><b>它可被校准</b>：真实的降级次数会落在 {@code consecutive_failures} 这个
     * 公开读数上（{@link ExperienceEntry#consecutiveFailures()}）⇒ 攒够样本后回来改这个常数，
     * 而不是再去猜一个「更准的」数。</p>
     *
     * <p><b>为什么按「连续」而不是「累计」</b>：累计失败里混着「后来被成功推翻」的旧失败，
     * 拿它降级会误伤；连续失败问的是「最近这一段有多不对」，两者判据不同
     * （{@code counterexamples} 保留全部证据，不参与降级判定）。</p>
     */
    public static final int DEMOTE_AFTER_CONSECUTIVE_FAILURES = 3;

    private final Path file;
    private final List<ExperienceEntry> mirror = new ArrayList<>();
    private boolean loaded;

    /**
     * 「这条经验被呈现过」的回执表（E7 前半段）。
     *
     * <p>放在 store 里而不是另开一个类持有，是因为它<b>天生按同伴隔离</b>：
     * 每个 store 就对应一只同伴的一个文件，天然没有跨同伴串台的风险。
     * 不落盘（跨重启的那次任务早就结束，回报已无意义）。</p>
     */
    private final PresentationReceipt receipts = new PresentationReceipt();

    private ExperienceStore(Path file) {
        this.file = file;
    }

    public static ExperienceStore at(Path file) {
        return new ExperienceStore(file);
    }

    public Path file() {
        return file;
    }

    /**
     * 本同伴的呈现回执表（E7：让「这条经验被用过」有连接键）。
     *
     * <p>此前三个召回点（召回工具 / L0 目录 / 规划知识）都不记「谁被用过」，
     * 于是 AI 没有理由知道该回报哪一条 ⇒ {@link #recordEvidence} 一直等不到调用。</p>
     */
    public PresentationReceipt receipts() {
        return receipts;
    }

    /** 全部经验（插入序快照）。 */
    public synchronized List<ExperienceEntry> all() {
        ensureLoaded();
        return List.copyOf(mirror);
    }

    public synchronized int size() {
        ensureLoaded();
        return mirror.size();
    }

    /**
     * 写入或合并一条经验。同 {@code id} 已存在时合并：描述类字段取新值、触发词/工具/标签
     * 并集、成熟度与验证次数取更高者——合并绝不清掉已积累的证据。
     *
     * <p><b>去重是两级的</b>，缺一不可：
     * <ol>
     *   <li><b>id 归一</b>：不管调用方给的 id 是什么，一律按
     *       {@link ExperienceEntry#stableKey(type, title)} 重算。
     *       否则调用方（或历史数据）换个拼写就绕过去重。</li>
     *   <li><b>指纹兜底</b>：id 没撞上但 {@link ExperienceEntry#fingerprint} 撞上了
     *       （同一个标题的不同写法：多空格/全角半角/首尾标点），也算同一条。
     *       这一层吸收「同一条经验被重写一遍」的漂移。</li>
     * </ol>
     *
     * @return 实际落库的那条（可能是合并结果）
     */
    public synchronized ExperienceEntry learn(ExperienceEntry entry) {
        if (entry == null || entry.type() == null || entry.title() == null || entry.title().isBlank()
                || entry.description() == null || entry.description().isBlank()) {
            throw new IllegalArgumentException("experience requires type, title and description");
        }
        ensureLoaded();
        ExperienceEntry normalized = canonicalize(entry);

        int index = indexOf(normalized.id());
        if (index < 0) {
            // ★ 空标题不许走指纹兜底：空归一 + 空标题会命中别的空标题条目，
            //   而 learn() 已经要求 title 非空，所以这里只能是「归一后为空的边界输入」。
            String fp = ExperienceEntry.fingerprint(normalized.type(), normalized.title());
            if (!fp.endsWith("|")) {
                int byPrint = indexOfFingerprint(fp);
                if (byPrint >= 0) {
                    index = byPrint;
                    LOG.info("[experience] title drifted but fingerprint matches existing '{}' -> merging as same entry",
                            mirror.get(byPrint).title());
                }
            }
        }
        ExperienceEntry stored;
        if (index < 0) {
            stored = normalized;
            mirror.add(stored);
            LOG.info("[experience] learned '{}' ({})", stored.title(), stored.type());
        } else {
            stored = merge(mirror.get(index), normalized);
            mirror.set(index, stored);
            LOG.info("[experience] merged '{}' (now {})", stored.title(), stored.maturity());
        }
        persist();
        return stored;
    }

    /**
     * 记录一次真实世界的验证结果。
     *
     * <pre>{@code
     * success=true ：verifiedCount++；成熟度按证据累计升级
     *               (OBSERVED/ATTEMPTED→VERIFIED，verifiedCount≥3→GENERALIZED)
     * success=false：追加反例（封顶 MAX_COUNTEREXAMPLES）；OBSERVED→ATTEMPTED；不降级
     * }</pre>
     *
     * @param id 经验 id。**认不出就按旧拼写兜底**（把 {@code '|'} 后半段当 title 比对），
     *           否则归一化之前流出去的旧 id 会永远打不中。
     * @return 更新后的经验；id 不存在返回 {@code null}
     */
    public synchronized ExperienceEntry recordEvidence(String id, boolean success, String note) {
        ensureLoaded();
        // ★ E7：一次「回报」不管成没成都算数 —— 连 id 打不中也要清掉待回报，
        //   否则 AI 会一遍遍看到同一条根本不存在的 id，反复来问同一个问题。
        receipts.report(id);
        int index = indexOf(id);
        if (index < 0) {
            index = indexByLegacyId(id);
            if (index >= 0) {
                LOG.info("[experience] legacy id '{}' resolved to '{}' by title",
                        id, mirror.get(index).id());
            }
        }
        if (index < 0) {
            return null;
        }
        ExperienceEntry old = mirror.get(index);
        List<String> counterexamples = old.counterexamples();
        ExperienceMaturity maturity = old.maturity();
        int verifiedCount = old.verifiedCount();
        long verifiedAt = old.verifiedAt();
        int consecutiveFailures = old.consecutiveFailures();
        boolean demoted = false;

        if (success) {
            verifiedCount++;
            verifiedAt = System.currentTimeMillis();
            maturity = maturityFor(verifiedCount);
            // 成功不清旧反例（那是证据），但「连续」这个计数归零：
            // 一次成功之后，下一次失败不该和上上次的失败被算成连续。
            consecutiveFailures = 0;
            // 成功这一侧仍然只升不降（这里才加这句 —— 失败那一侧见下）
            if (maturity.level() < old.maturity().level()) {
                maturity = old.maturity();
            }
            // ⚠️ note 在成功侧**不落进条目**（失败侧才进 counterexamples，见下）。
            //   59 号 §6.2 要求每条经验带证据引用，成功时那句 note 就是证据 ——
            //   丢掉它等于「知道对了，但不知道为什么对」。
            //   ★ 为什么不加字段补上：ExperienceEntry.toJson 是 21 键的跨插件契约，
            //   learner 侧的 ExperienceDraft.ENTRY_KEYS 与
            //   ExperienceDraftKeysBindToRealEntryTest 都钉着这个数，加一键要三处同步改，
            //   风险远大于收益。成功侧的 note 改为进 expmem.jsonl 的 verified 埋点
            //   （证据可查回，只是不在条目里）。已记进记忆图谱的待办。
        } else {
            String reason = (note == null || note.isBlank()) ? "unconfirmed" : note;
            Set<String> seen = new LinkedHashSet<>(counterexamples);
            seen.add(reason);
            if (seen.size() > MAX_COUNTEREXAMPLES) {
                seen.remove(seen.iterator().next());
            }
            counterexamples = List.copyOf(seen);
            consecutiveFailures++;
            // ★ E8 修的真洞：老代码这里只有下面那句 `if (old < ATTEMPTED) maturity = ATTEMPTED;`
            //   ⇒ 只有 OBSERVED 会被标成 ATTEMPTED，VERIFIED/GENERALIZED **完全不受失败影响**。
            //   实测后果：一条被证伪 10 次的 GENERALIZED 经验成熟度永不下降，
            //   注入侧照样报 <verified>N</verified> 当它可信 —— 那是假事实。
            if (consecutiveFailures >= DEMOTE_AFTER_CONSECUTIVE_FAILURES
                    && maturity.level() > ExperienceMaturity.ATTEMPTED.level()) {
                maturity = demote(maturity);
                consecutiveFailures = 0;
                demoted = true;
            } else if (maturity.level() < ExperienceMaturity.ATTEMPTED.level()) {
                // OBSERVED 被证伪一次就至少是 ATTEMPTED（老行为，保留）
                maturity = ExperienceMaturity.ATTEMPTED;
            }
        }

        ExperienceEntry updated = old.withEvidence(maturity, verifiedCount, verifiedAt, counterexamples)
                .withConsecutiveFailures(consecutiveFailures);
        mirror.set(index, updated);
        persist();
        if (demoted) {
            LOG.info("[experience] '{}' demoted to {} after {} consecutive failures",
                    updated.title(), updated.maturity(), DEMOTE_AFTER_CONSECUTIVE_FAILURES);
        }
        return updated;
    }

    /**
     * 成熟度降一级（E8）。
     *
     * <p>⚠️ 只在失败那一侧调用；<b>不会降到 OBSERVED 以下</b>。
     * 已撤回的条目不靠降级来处理 —— 撤回是更强的信号（「这条根本不该用」），
     * 降级反而是弱化表述；撤回状态由 {@link ExperienceEntry#unusableReason(boolean)} 在过滤侧表达。</p>
     */
    private static ExperienceMaturity demote(ExperienceMaturity m) {
        ExperienceMaturity[] order = ExperienceMaturity.values();
        int i = m.ordinal();
        return (i > 0) ? order[i - 1] : m;
    }

    // ============================================================
    // E8：撤回 / 修订链
    // ============================================================

    /**
     * 撤回一条经验（E8）——<b>标记，不删数据</b>。
     *
     * <p>⚠️ 为什么是标记而不是删除：删了就没有「它曾经存在过、为什么被撤」的证据，
     * 而这个项目要的正是「整个链路都要冻结」（与 B6 同源）。
     * 撤回后 {@link #usable()} 不再收它，注入侧也会如实说它已撤回。</p>
     *
     * @param reason <b>必填且不许空</b> —— 撤回一条经验而不写理由，等于让别人猜
     * @return 撤回后的条目；id 不存在时返回 {@code null}（不静默成功）
     */
    public synchronized ExperienceEntry retract(String id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("retract requires a reason: id=" + id);
        }
        ensureLoaded();
        int index = indexOf(id);
        if (index < 0) {
            return null;
        }
        ExperienceEntry old = mirror.get(index);
        if (old.retracted()) {
            // 幂等：已撤回再撤一次 = 改理由（误撤回能纠正），不报错也不静默丢弃
            LOG.info("[experience] '{}' already retracted; reason updated", old.title());
        }
        ExperienceEntry updated = old.retracted(reason.trim());
        mirror.set(index, updated);
        persist();
        LOG.info("[experience] retracted '{}' ({})", updated.title(), reason.trim());
        return updated;
    }

    /**
     * 撤销撤回（E8）：误撤回要能救回来。
     *
     * <p>⚠️ 撤销会清掉 {@code retractedAt/Reason}（{@code unretracted()}）——
     * 保留「曾被撤回过」需要另一个字段，那不在这一批的范围。这一批的取舍是：
     * <b>状态干净优先</b>，所以清掉；要留痕就靠 {@link #retractLog} 之外的审计日志。</p>
     */
    public synchronized ExperienceEntry reinstate(String id) {
        ensureLoaded();
        int index = indexOf(id);
        if (index < 0) {
            return null;
        }
        ExperienceEntry old = mirror.get(index);
        if (!old.retracted()) {
            return old;
        }
        ExperienceEntry updated = old.unretracted();
        mirror.set(index, updated);
        persist();
        LOG.info("[experience] reinstated '{}'", updated.title());
        return updated;
    }

    /**
     * 标注「新条目取代了旧条目」（E8 修订链）。
     *
     * <p><b>只存单向</b>：新条目记 {@code supersedes=旧id}。「旧的被取代了」
     * 用 {@link #supersededIds()} 现算 ⇒ 不存第二份「被取代」状态，
     * 也就不会出现两份数据互相矛盾。</p>
     *
     * @return 被更新的旧条目；旧 id / 新 id 任一不存在，或新条目已撤回 ⇒ {@code null}
     */
    public synchronized ExperienceEntry supersede(String olderId, String newerId) {
        ensureLoaded();
        if (olderId == null || olderId.isBlank() || newerId == null || newerId.isBlank()) {
            throw new IllegalArgumentException("supersede requires both ids");
        }
        if (olderId.equals(newerId)) {
            throw new IllegalArgumentException("an entry cannot supersede itself: " + newerId);
        }
        int newerIndex = indexOf(newerId);
        if (newerIndex < 0) {
            return null;
        }
        if (indexOf(olderId) < 0) {
            // 指向不存在的旧条目 = 说谎，直接拒绝（否则这条链永远是断的）
            LOG.info("[experience] reject supersede: older id '{}' not found (newer='{}')",
                    olderId, newerId);
            return null;
        }
        ExperienceEntry newer = mirror.get(newerIndex);
        if (newer.retracted()) {
            LOG.info("[experience] reject supersede: newer '{}' is retracted", newerId);
            return null;
        }
        ExperienceEntry updated = newer.supersedes(olderId);
        mirror.set(newerIndex, updated);
        persist();
        LOG.info("[experience] '{}' now supersedes '{}'", updated.title(), olderId);
        return mirror.get(indexOf(olderId));
    }

    /** 已撤回的条数（E8）。 */
    public synchronized int retractedCount() {
        ensureLoaded();
        int n = 0;
        for (ExperienceEntry e : mirror) {
            if (e.retracted()) {
                n++;
            }
        }
        return n;
    }

    /** 已被更新条目取代的条数（E8）。 */
    public synchronized int supersededCount() {
        return supersededIds().size();
    }

    /** 当前被取代掉的 id 集合（E8 修订链，由 {@code supersedes} 现算，不落盘）。 */
    public synchronized java.util.Set<String> supersededIds() {
        ensureLoaded();
        java.util.Set<String> out = new LinkedHashSet<>();
        for (ExperienceEntry e : mirror) {
            String s = e.supersedes();
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 还能当可信经验用的那些（E8）：<b>排除已撤回与已被取代的</b>。
     *
     * <p>⚠️ 默认<b>收窄</b>是故意的：要「全量视图」（监测台要看到已撤回的那些）请用 {@link #all()}。</p>
     */
    public synchronized List<ExperienceEntry> usable() {
        ensureLoaded();
        java.util.Set<String> dead = supersededIds();
        java.util.List<ExperienceEntry> out = new java.util.ArrayList<>();
        for (ExperienceEntry e : mirror) {
            if (e.unusableReason(dead.contains(e.id())).isEmpty()) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    // ---- internals ----

    /** 按验证次数推出成熟度（成功视角）。 */
    private static ExperienceMaturity maturityFor(int verifiedCount) {
        if (verifiedCount >= GENERALIZED_THRESHOLD) {
            return ExperienceMaturity.GENERALIZED;
        }
        if (verifiedCount >= 1) {
            return ExperienceMaturity.VERIFIED;
        }
        return ExperienceMaturity.ATTEMPTED;
    }

    private int indexOf(String id) {
        for (int i = 0; i < mirror.size(); i++) {
            if (mirror.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把一条经验的 id 强制按 {@code stableKey(type,title)} 重算。
     *
     * <p><b>磁盘上的 id 一律不可信</b> —— 它是历史遗留物，可能来自旧拼写、
     * 手工 seed 或别的写入方。留着它就等于给去重开后门。</p>
     */
    private static ExperienceEntry canonicalize(ExperienceEntry e) {
        String canonical = ExperienceEntry.stableKey(e.type(), e.title());
        if (canonical.equals(e.id())) {
            return e;
        }
        return ExperienceEntry.builder().from(e).id(canonical).build();
    }

    /**
     * 按指纹找「同一条经验」（同一个标题的不同写法）。
     *
     * <p>线性扫 —— 经验是小数据量（实测 22 条），{@link #indexOf(String)} 本来也是线性的，
     * 保持同一复杂度不引入第二套索引。</p>
     */
    private int indexOfFingerprint(String fingerprint) {
        for (int i = 0; i < mirror.size(); i++) {
            if (mirror.get(i).fingerprint().equals(fingerprint)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 旧拼写 id 的兜底查找：<b>解析前缀 → 认别名 → 按 canonical id 找</b>。
     *
     * <p>存在的理由：归一化之前已经流出去的 id（比如写进过日志、监测台、人工记录的
     * {@code tooldefect|xxx}）在磁盘条目被重写后不再是 id，直接返回「找不到」会让那些调用方静默失败。</p>
     *
     * <p>⚠️ <b>绝不能只比对 {@code '|'} 后半段</b>：那样 {@code recordEvidence("tooldefect|t")}
     * 会命中镜像里第一条同名经验，哪怕它是 {@code POLICY} —— 证据就记到错的类型上，
     * 还会把它误升成 VERIFIED。未知前缀一律拒绝，宁可返回「找不到」。</p>
     */
    private int indexByLegacyId(String id) {
        if (id == null) {
            return -1;
        }
        int bar = id.indexOf('|');
        if (bar <= 0 || bar == id.length() - 1) {
            return -1;
        }
        ExperienceType prefix = resolveTypePrefix(id.substring(0, bar));
        if (prefix == null) {
            return -1;
        }
        return indexOf(ExperienceEntry.stableKey(prefix, id.substring(bar + 1)));
    }

    /**
     * id 前缀 → 枚举值。认 canonical 名（大小写不敏感）与已知旧别名；其余一律 {@code null}。
     *
     * <p>别名表只收<b>实测见过</b>的拼写，不做「去掉下划线」这种通配 ——
     * 通配会把任意未知前缀变成合法类型，那比不兜底更危险。</p>
     */
    private static ExperienceType resolveTypePrefix(String prefix) {
        String p = prefix.trim().toLowerCase();
        for (ExperienceType t : ExperienceType.values()) {
            if (t.name().toLowerCase().equals(p)) {
                return t;
            }
        }
        return switch (p) {
            case "tooldefect" -> ExperienceType.TOOL_DEFECT;
            case "worldrelation" -> ExperienceType.WORLD_RELATION;
            default -> null;
        };
    }

    private ExperienceEntry merge(ExperienceEntry old, ExperienceEntry fresh) {
        ExperienceType type = fresh.type() == null ? old.type() : fresh.type();
        String title = fresh.title();
        // ★ id 必须跟着 title 走：留着 old.id() 会出现「id 与 title 不自洽」——
        //   stableKey(type,title) 算不出这个 id，下次重载时 addFromObject 会再归一一次，
        //   于是同一个条目在两次重载之间 id 漂移，归一化前流出去的旧引用全部失效。
        //
        // ★★ 起手必须是 `from(old)` 而不是空 builder（2026-10-04 修的真洞）。
        //   这一版原来从零逐个 setter 拼，于是**七字段槽位与 E8 元数据全部被静默清空**：
        //   可复现后果①learn(有七字段) → learn(同 type/title、七字段空) ⇒ 七槽位全变 ""；
        //   可复现后果②**retract(id,"理由") → learn(同 type/title) ⇒ retracted() 变回 false，
        //   一条被人工撤回的经验复活并重新进入 usable()**。
        //   而 `ExperienceEntry.Builder.from()`（:449-484）本来就搬全了这两组，
        //   `ExperienceEntry` 的注释（:463/:478）也写着「必须一起搬」——
        //   它守住了 withEvidence/retracted/unretracted/supersedes/withConsecutiveFailures
        //   五条变异路径，唯独漏了 merge() 这条。
        return ExperienceEntry.builder()
                .from(old)
                .id(ExperienceEntry.stableKey(type, title))
                .type(type)
                .title(title)
                .description(fresh.description())
                .rationale(blank(fresh.rationale()) ? old.rationale() : fresh.rationale())
                .rootCause(blank(fresh.rootCause()) ? old.rootCause() : fresh.rootCause())
                .recommendedResponse(blank(fresh.recommendedResponse()) ? old.recommendedResponse() : fresh.recommendedResponse())
                // ★ 七字段沿用与 rationale/rootCause 同一套「新的非空则新的，否则留旧的」。
                //   不做「新的永远覆盖」：那会把一次空草稿变成抹掉别人写好的正文。
                .mechanism(blank(fresh.mechanism()) ? old.mechanism() : fresh.mechanism())
                .preconditions(blank(fresh.preconditions()) ? old.preconditions() : fresh.preconditions())
                .failureConditions(blank(fresh.failureConditions()) ? old.failureConditions() : fresh.failureConditions())
                .observableSignal(blank(fresh.observableSignal()) ? old.observableSignal() : fresh.observableSignal())
                .derivation(blank(fresh.derivation()) ? old.derivation() : fresh.derivation())
                .efficiency(blank(fresh.efficiency()) ? old.efficiency() : fresh.efficiency())
                .evidence(blank(fresh.evidence()) ? old.evidence() : fresh.evidence())
                .triggerStrings(union(old.triggerStrings(), fresh.triggerStrings()))
                .toolNames(union(old.toolNames(), fresh.toolNames()))
                .tags(union(old.tags(), fresh.tags()))
                .maturity(ExperienceMaturity.max(old.maturity(), fresh.maturity()))
                .verifiedCount(Math.max(old.verifiedCount(), fresh.verifiedCount()))
                .priority(Math.max(old.priority(), fresh.priority()))
                .counterexamples(cappedUnion(old.counterexamples(), fresh.counterexamples()))
                .createdAt(old.createdAt())
                .verifiedAt(fresh.verifiedAt() > 0 ? fresh.verifiedAt() : old.verifiedAt())
                .lastAccessedAt(System.currentTimeMillis())
                // ★★ 撤回是**不可逆**的：一次 learn 不许把它撤销。
                //   这里用 or 而不是「新的优先」——后者就是「撤回的经验复活」那个洞。
                //   要恢复一条被撤回的经验只有一条路：显式 unretract()。
                .retracted(old.retracted() || fresh.retracted())
                .retractedAt(old.retractedAt() > 0 ? old.retractedAt() : fresh.retractedAt())
                .retractedReason(!blank(old.retractedReason()) ? old.retractedReason() : fresh.retractedReason())
                // supersedes 是「我取代了谁」的指针，learn 不该把它抹掉
                .supersedes(!blank(old.supersedes()) ? old.supersedes() : fresh.supersedes())
                // 累计失败数取大：一次新 learn 不该把三次连败的记录清零
                .consecutiveFailures(Math.max(old.consecutiveFailures(), fresh.consecutiveFailures()))
                .build();
    }

    /**
     * 反例<b>并集</b>，再按 {@link #MAX_COUNTEREXAMPLES} 截断。
     *
     * <p>⚠️ 原来是 {@code fresh.isEmpty() ? old : fresh} —— 后来者赢。
     * 读时合并（两条原本 id 不同、归一后撞车的记录）会走到这里，
     * 于是先那条的反例被整条顶掉，persist 一下就永久没了。
     * <b>反例是「证明这条经验不总是对的」的唯一记录，宁可多留。</b></p>
     */
    private static List<String> cappedUnion(List<String> a, List<String> b) {
        List<String> all = union(a, b);
        if (all.size() <= MAX_COUNTEREXAMPLES) {
            return all;
        }
        return List.copyOf(all.subList(all.size() - MAX_COUNTEREXAMPLES, all.size()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> union(List<String> a, List<String> b) {
        Set<String> seen = new LinkedHashSet<>(a);
        seen.addAll(b);
        return List.copyOf(seen);
    }

    /**
     * 加载统计。
     *
     * <p><b>五个数必须分开</b>，因为它们对应五种不同的病：
     * <ul>
     *   <li>{@code loadedLine} —— 逐行 JSONL 读出的（正常）</li>
     *   <li>{@code loadedLegacy} —— <b>形状与文件名不自洽</b>读出的
     *       （整个文件是一个 JSON 数组，或整个文件只有一条）。
     *       这些文件本该是「一行一条」，需要迁移。</li>
     *   <li>{@code loadedFailed} —— <b>真的解析失败</b>的（代码问题或数据被截断）。</li>
     *   <li>{@code rekeyed} —— <b>磁盘上的 id 与 (type,title) 对不上、被重算过</b>的。
     *       实测存量数据里有 {@code tooldefect|…} 这种无下划线的旧拼写，
     *       而 {@link ExperienceEntry#stableKey} 产出 {@code tool_defect|…} ——
     *       不归一的话它们永远匹配不上新写入，重复 learn 只会堆条目而不是合并。</li>
     *   <li>{@code duplicate} —— <b>归一后撞上同一条</b>、被合并掉的。
     *       原先这里是 {@code return false} 静默丢弃：证据被扔掉，
     *       而 {@code loadedLine} 照样 +1 ⇒ 报出来的条数比真有的多。</li>
     * </ul>
     *
     * <p><b>口径</b>：{@code loadedLine} / {@code loadedLegacy} 数的是
     * <b>成功解析进镜像的条目（含重复）</b>，解析失败的<b>只</b>进 {@code loadedFailed}；
     * 重复另外计在 {@code duplicate} 里，{@link #total()} 统一扣一次。
     * 这样三者相加减永远等于 {@code size()}，面板上的数不会自相矛盾。</p>
     *
     * <p>⚠️ 历史教训：原先 {@link RuntimeException} 被 catch 后只记一行日志，
     * 于是「文件存在但一条都读不进来」表现为 {@code size()==0} ——
     * <b>不报错，只是不工作</b>。现在这几个数是公开读数，异常可见。
     */
    public record LoadStats(int loadedLine, int loadedLegacy, int loadedFailed,
                            int rekeyed, int duplicate) {
        /** 只关心前三个数的调用方（向后兼容）。 */
        public LoadStats(int loadedLine, int loadedLegacy, int loadedFailed) {
            this(loadedLine, loadedLegacy, loadedFailed, 0, 0);
        }

        /**
         * 成功读出、且真的进了镜像的总条数（已扣掉被合并的重复）。
         *
         * <p>⚠️ 与 {@code size()} 的口径差：这几个数描述的是「<b>从磁盘读进来</b>多少」。
         * 新建库（磁盘上还没有文件）时它们全 0，而 {@code size()} 已经 ≥1 —— 那 1 条是本次写进去的。
         * 这是有意的，不是 bug；面板上要说清楚「读入 N 条」而不是含糊的「共 N 条」。
         */
        public int total() {
            return loadedLine + loadedLegacy - duplicate;
        }

        /** 是否有形状与后缀不自洽、需要迁移的文件。 */
        public boolean needsMigrate() {
            return loadedLegacy > 0;
        }

        /** 是否有真正解析失败的条目 —— 需要人看。 */
        public boolean degraded() {
            return loadedFailed > 0;
        }
    }

    private LoadStats loadStats = new LoadStats(0, 0, 0);

    /** 本次加载过程中的五个计数器（{@link #ensureLoaded()} 内部累加）。 */
    private int statLine;
    private int statLegacy;
    private int statFailed;
    private int statRekeyed;
    private int statDuplicate;

    /** 本次加载的统计（只读）。未加载过时是全 0。 */
    public synchronized LoadStats loadStats() {
        ensureLoaded();
        return loadStats;
    }

    /**
     * 加载磁盘经验，<b>兼容三种文件形状</b>。
     *
     * <p>为什么需要兼容：早期版本曾把整个 JSON 数组写进 {@code .jsonl} 后缀的文件
     * （一个文件只有 1 行、内容是数组）。读取端原先只认「逐行 JSONL」，
     * 对数组调 {@code getAsJsonObject()} 会抛 {@code IllegalStateException}，
     * 那唯一一行被静默跳过 ⇒ mirror 为空 ⇒ 注入侧 {@code total()==0} ⇒
     * <b>经验正文一条都进不了上下文</b>。
     *
     * <p>同类的坑学习者队列在 2026-09-29 已修（{@code MemoQueue} 改成非 {@code .jsonl}
     * 后缀），经验库当时漏了。
     */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        if (Files.isRegularFile(file)) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                LOG.warn("[experience] failed to read {}: {}", file, ex.toString());
                loaded = true;
                return;
            }
            statLine = 0;
            statLegacy = 0;
            statFailed = 0;
            statRekeyed = 0;
            statDuplicate = 0;
            // ★ 必须先清空：ensureLoaded() 可能被第二次调用（migrateLegacyShape() 走完会把
            //   loaded 置回 false）。不清的话每条都会撞上「自己那条」，被当成重复合并，
            //   于是 loadedLine=2 而 duplicate=2，total() 算成 0 —— 条数凭空消失。
            mirror.clear();

            // 先判形状：整个文件是一个 JSON 值时走兼容路径。
            JsonElement root = tryParse(text);
            if (root != null && root.isJsonArray()) {
                // 遗留：整个文件是一个 JSON 数组（.jsonl 后缀，但内容不是行分隔）
                for (JsonElement el : root.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        countLegacyOrFail(el.getAsJsonObject());
                    } else {
                        statFailed++;
                    }
                }
            } else if (root != null && root.isJsonObject()) {
                // 遗留：整个文件只有一条
                countLegacyOrFail(root.getAsJsonObject());
            } else {
                // 正常路径：一行一条
                for (String line : text.split("\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonObject o = tryParseObject(line);
                    if (o == null) {
                        statFailed++;
                        continue;
                    }
                    int rc = addFromObject(o);
                    if (rc == ADD_BAD) {
                        statFailed++;
                        continue;
                    }
                    // ★ 只有真的解析进镜像的才算「读出」——坏行不该混进 total()，
                    //   否则面板上的条数里混着解析失败的数量。
                    statLine++;
                    if (rc == ADD_DUP) {
                        statDuplicate++;
                    }
                }
            }
            loadStats = new LoadStats(statLine, statLegacy, statFailed, statRekeyed, statDuplicate);
            if (loadStats.needsMigrate()) {
                LOG.warn("[experience] {} is not line-delimited JSONL (loaded {} via legacy shape); "
                        + "call migrateLegacyShape() to convert it", file, statLegacy);
            }
            if (loadStats.degraded()) {
                LOG.warn("[experience] {}: {} entries failed to parse (store is degraded, not empty)",
                        file, statFailed);
            }
            if (statRekeyed > 0) {
                LOG.info("[experience] {}: re-keyed {} entries to stableKey(type,title) "
                                + "(legacy id spelling no longer matches writes)",
                        file.getFileName(), statRekeyed);
            }
            if (statDuplicate > 0) {
                LOG.info("[experience] {}: merged {} duplicate entries (same canonical id)",
                        file.getFileName(), statDuplicate);
            }
        }
        loaded = true;
    }

    private static JsonElement tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 逐行路径用：必须顶层是 object 才是「一条经验」。 */
    private static JsonObject tryParseObject(String line) {
        try {
            JsonElement e = JsonParser.parseString(line);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@link #addFromObject} 的结果码：三条路径要分开计数，混在一起就看不出是哪一种病。 */
    private static final int ADD_OK = 0;
    private static final int ADD_DUP = 1;
    private static final int ADD_BAD = 2;

    /** 遗留路径用：成功解析的都算 legacy（<b>含重复</b>），撞 id 另计 duplicate。 */
    private void countLegacyOrFail(JsonObject o) {
        int rc = addFromObject(o);
        if (rc != ADD_BAD) {
            statLegacy++;
        }
        if (rc == ADD_DUP) {
            statDuplicate++;
        }
    }

    /**
     * 把一条 JSON object 加进镜像，<b>先按 (type,title) 归一 id</b>，再按 id 去重。
     *
     * <p>为什么必须归一：磁盘上的 {@code id} 是历史遗留物，不是可信来源。
     * 实测存量条目里有 {@code tooldefect|…} 这种无下划线的旧拼写，
     * 而 {@link ExperienceEntry#stableKey} 产出 {@code tool_defect|…} ——
     * 两套拼写并存时，重复写入一条已有经验<b>不会合并，而是变成新的一条</b>。
     * 归一之后 {@code indexOf} 才有意义。</p>
     *
     * <p>撞 id 时<b>合并</b>而不是丢弃：原先 {@code return false} 会把后来那条的
     * 反例/触发词/更成熟的 maturity 一起扔掉，而 {@code loadedLine} 照样 +1，
     * 于是「磁盘 59 条 / 代码可读 0 条」这类口径偏差无法被发现。</p>
     *
     * <p>⚠️ {@code type} 解析不出来（未知枚举）时<b>不归一</b>：{@code stableKey(null, …)}
     * 会产出 {@code unknown|title}，把一条本来 id 正确的未知类型条目改成另一个 id，
     * 还会和另一条同标题的未知类型条目撞车合并 —— 那是在删数据。保留原 id 并记警告。</p>
     *
     * @return {@link #ADD_OK} / {@link #ADD_DUP} / {@link #ADD_BAD}
     */
    private int addFromObject(JsonObject o) {
        try {
            ExperienceEntry e = ExperienceEntry.fromJson(o);
            if (e.id() == null || e.id().isBlank() || e.title() == null || e.title().isBlank()) {
                return ADD_BAD;
            }
            if (e.type() == null) {
                LOG.warn("[experience] entry '{}' has an unknown/absent type — keeping id '{}' as-is, "
                                + "not canonicalized (would collapse into unknown|title)",
                        e.title(), e.id());
            } else {
                String canonical = ExperienceEntry.stableKey(e.type(), e.title());
                if (!canonical.equals(e.id())) {
                    statRekeyed++;
                    e = canonicalize(e);
                }
            }
            int i = indexOf(e.id());
            if (i < 0) {
                mirror.add(e);
                return ADD_OK;
            }
            mirror.set(i, merge(mirror.get(i), e));
            return ADD_DUP;
        } catch (RuntimeException ex) {
            LOG.warn("[experience] unparsable entry in {}: {}", file.getFileName(), ex.toString());
            return ADD_BAD;
        }
    }

    /**
     * 一次性迁移「形状与文件名不自洽」的遗留文件：整个 JSON 数组 → 逐行 JSONL。
     *
     * <p>安全约束（用户存档不可再丢）：
     * <ol>
     *   <li><b>先备份</b>，且<b>仅当备份不存在时</b>才建（否则反复迁移会刷一堆备份）</li>
     *   <li><b>原子替换</b>，走与 {@link #persist()} 同一条 temp + {@code ATOMIC_MOVE} 路径</li>
     *   <li><b>幂等</b>：迁完再读，走正常逐行路径，{@code loadedLegacy} 变 0</li>
     * </ol>
     *
     * @return 迁移后的条数；本来就不需要迁移时返回 {@code loadStats().total()}
     */
    public synchronized int migrateLegacyShape() {
        ensureLoaded();
        if (!loadStats.needsMigrate()) {
            return loadStats.total();
        }
        Path backup = file.resolveSibling(file.getFileName() + ".pre-migrate-" + System.currentTimeMillis() + ".bak");
        try {
            if (!Files.exists(backup)) {
                Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
                LOG.info("[experience] backed up legacy-shaped {} to {}", file, backup.getFileName());
            }
            persist();   // 逐行 JSONL + temp + ATOMIC_MOVE
            int n = loadStats.total();
            LOG.info("[experience] migrated {} from legacy array shape to line-delimited JSONL ({} entries)",
                    file.getFileName(), n);
            loaded = false;      // 让下一次读走正常路径，loadStats 随之刷新
            return n;
        } catch (IOException ex) {
            LOG.warn("[experience] failed to migrate {}: {}", file, ex.toString());
            return loadStats.total();
        }
    }

    /** 全量原子重写：temp 文件 → ATOMIC_MOVE 替换。 */
    private void persist() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder(mirror.size() * 128);
            for (ExperienceEntry e : mirror) {
                sb.append(e.toJson()).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            LOG.warn("[experience] failed to persist {}: {}", file, ex.toString());
        }
    }
}
