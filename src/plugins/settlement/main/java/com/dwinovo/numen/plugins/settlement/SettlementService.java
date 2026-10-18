package com.dwinovo.numen.plugins.settlement;

import com.dwinovo.numen.settlement.core.json.SettlementJson;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;
import com.dwinovo.numen.settlement.core.protect.Grant;
import com.dwinovo.numen.settlement.core.protect.GrantLedger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 设施登记的宿主侧真源：读写 {@code config/numen/settlement/facilities.json}。
 *
 * <p><b>独立于 {@code AssetRegistry}</b>——那里有 128 条淘汰上限，永久登记的基地和保护规则
 * 绝不能跟着普通观测一起被淘汰。写盘用 tmp + 原子替换；内存快照整份替换，读侧拿到的
 * 不可变表不会被写侧改动。
 */
public final class SettlementService {

    private final Path file;
    private final Path baseFile;
    private volatile FacilityRegistry registry;
    /** 临时施工/维修授权；不落盘（进程内、时间盒），重启即失效。 */
    private final List<Grant> grants = new ArrayList<>();
    /**
     * 施工授权账本（基建工具 V1）：按 (设施, 任务) 记账，才能"所有终止路径回收"。
     * 只往 {@link #grants} 塞列表的话，任务被顶替时没人负责撤销，授权会一直挂着。
     */
    private final GrantLedger grantLedger = new GrantLedger();
    /** 基地基准网格（一块地皮的网格定义），落盘 base.json。 */
    private volatile BaseGrid base;
    /**
     * 自动关门的暂停截止（毫秒）；0=不暂停。<b>不落盘，重启即失效。</b>
     *
     * <p>为什么必须有：赶羊进圈时，人穿过门后 {@code GateWatcher} 约 1 秒就把门关了，
     * 跟在后面的羊会被<b>锁在门外</b>（实测 v6-v8 引羊 n=0 的直接原因之一）。
     * 引羊 AC 开局把暂停开上（带超时兜底），收尾关掉暂停并自己出门关门。
     */
    private volatile long gatesHoldUntilMs = 0L;

    public long gatesHoldUntilMs() { return gatesHoldUntilMs; }
    public void setGatesHeld(long untilMs) { this.gatesHoldUntilMs = untilMs > 0 ? untilMs : 0L; }
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** 基地基准：一块地皮的固定原点/朝向/每格边长/行列数。 */
    public record BaseGrid(String dimension, int originX, int floorY, int originZ,
                           int cols, int rows, int cellSize) {}

    public SettlementService(Path configDir) {
        this.file = configDir.resolve("settlement").resolve("facilities.json");
        this.baseFile = configDir.resolve("settlement").resolve("base.json");
        this.registry = load();
        this.base = loadBase();
    }

    public Path file() {
        return file;
    }

    public synchronized List<FacilityRecord> list() {
        return registry.all();
    }

    public synchronized Optional<FacilityRecord> byId(String id) {
        return registry.byId(id);
    }

    public synchronized FacilityRegistry registry() {
        return registry;
    }

    /** 当前有效的临时授权（顺手清掉过期的）。保护判定与执行拦截都读它。 */
    public synchronized List<Grant> activeGrants() {
        long now = System.currentTimeMillis();
        grants.removeIf(g -> g.expiresAtEpochMs() > 0 && now > g.expiresAtEpochMs());
        grantLedger.sweepExpired(now);
        // 账本里的（任务绑定）与手工加的合并；同一设施以账本为准，避免重复授权。
        List<Grant> out = new ArrayList<>(grantLedger.activeGrants());
        for (Grant g : grants) {
            boolean shadowed = false;
            for (Grant lg : out) {
                if (lg.facilityId().equals(g.facilityId())) {
                    shadowed = true;
                    break;
                }
            }
            if (!shadowed) out.add(g);
        }
        return List.copyOf(out);
    }

    public synchronized void addGrants(List<Grant> newGrants) {
        long now = System.currentTimeMillis();
        grants.removeIf(g -> g.expiresAtEpochMs() > 0 && now > g.expiresAtEpochMs());
        grants.addAll(newGrants);
    }

    /** 施工授权账本（基建工具 V1 用）。 */
    public GrantLedger grantLedger() {
        return grantLedger;
    }

    /**
     * 按"当前还在跑的任务"清扫施工授权——所有终止路径回收的机器判据。
     *
     * @return 被回收的设施 id
     */
    public synchronized List<String> sweepGrants(String currentTaskId) {
        List<String> released = new ArrayList<>(grantLedger.sweep(currentTaskId));
        released.addAll(grantLedger.sweepExpired(System.currentTimeMillis()));
        // 手工加的授权也按设施 id 一起撤（账本撤了就说明任务没了）。
        if (!released.isEmpty()) {
            grants.removeIf(g -> released.contains(g.facilityId()));
        }
        return released;
    }

    /** 撤销某设施（或全部，{@code facilityId==null}）的临时授权，返回撤销条数。 */
    public synchronized int clearGrants(String facilityId) {
        int before = grants.size();
        grants.removeIf(g -> facilityId == null || facilityId.equals(g.facilityId()));
        int cleared = before - grants.size();
        if (facilityId == null) {
            cleared += grantLedger.size();
            grantLedger.clear();
        } else if (grantLedger.release(facilityId)) {
            cleared++;
        }
        return cleared;
    }

    /** 登记或覆盖，立即落盘。 */
    public synchronized FacilityRecord register(FacilityRecord record) {
        registry = registry.with(record);
        save();
        return record;
    }

    /** 注销；登记里没有这个 id 返回 false，不写盘。 */
    public synchronized boolean unregister(String id) {
        if (registry.byId(id).isEmpty()) return false;
        registry = registry.without(id);
        save();
        return true;
    }

    public synchronized Optional<BaseGrid> base() {
        return Optional.ofNullable(base);
    }

    /**
     * 基地基准 → 网格计划（放置坐标解算用）。没有登记基准时返回空——
     * 放置入口会据此明确拒绝，而不是拿同伴站位当原点（那正是"原点漂移"的成因）。
     */
    public Optional<PlatformPlan> platformPlan() {
        BaseGrid g = base;
        if (g == null) return Optional.empty();
        return Optional.of(new PlatformPlan("base", g.dimension(), g.originX(), g.floorY(),
                g.originZ(), g.cols(), g.rows(), g.cellSize(), 0, 4, List.of(), List.of()));
    }

    public synchronized void setBase(BaseGrid grid) {
        this.base = grid;
        saveBase();
    }

    private BaseGrid loadBase() {
        try {
            if (Files.isRegularFile(baseFile)) {
                return GSON.fromJson(Files.readString(baseFile, StandardCharsets.UTF_8), BaseGrid.class);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    private void saveBase() {
        try {
            Files.createDirectories(baseFile.getParent());
            Path tmp = baseFile.resolveSibling(baseFile.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(base), StandardCharsets.UTF_8);
            Files.move(tmp, baseFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("cannot save base grid to " + baseFile, e);
        }
    }

    private FacilityRegistry load() {
        try {
            if (Files.isRegularFile(file)) {
                return SettlementJson.fromJson(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // 坏掉的可选登记缓存不拖垮插件启动（与 RddAssetStore 同纪律）。
        }
        return new FacilityRegistry();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, SettlementJson.toJson(registry), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("cannot save settlement registry to " + file, e);
        }
    }
}
