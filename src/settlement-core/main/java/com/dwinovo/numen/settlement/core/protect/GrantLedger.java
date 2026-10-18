package com.dwinovo.numen.settlement.core.protect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 施工授权的生命周期账本（纯逻辑）——用户点名的第 ④ 条：授权绑定实际施工任务，
 * <b>所有终止路径回收</b>；停止、重启、重复请求不会乱账。
 *
 * <p>为什么要单独记账而不是只把 {@link Grant} 塞进列表：授权必须能按
 * {@code (设施, 任务)} 精确回收。只塞列表的话，"任务被替换成别的活了"这种情况
 * 没人负责撤销，授权就一直挂着——等于把保护区永久打开，这正是最危险的形态。
 *
 * <p>三条终止路径都要收：
 * <ol>
 *   <li>任务正常结束（完成/失败/超时）；</li>
 *   <li>任务被别的动作顶替（槽里换了别的 task）；</li>
 *   <li>进程重启（内存 Grant 本就不落盘，这里额外保证账本也不留残留）。</li>
 * </ol>
 *
 * <p>本类不依赖任何 Minecraft 类型：宿主把"当前任务 id"喂进来，本类回答"该撤哪几条"。
 */
public final class GrantLedger {

    /** 一条开着的施工授权。 */
    public record OpenGrant(String facilityId, String taskId, Grant grant) { }

    private final Map<String, OpenGrant> byFacility = new LinkedHashMap<>();

    /** 开一条授权；同一设施重复开会替换旧的（旧授权先被回收，不留两条）。 */
    public synchronized void open(String facilityId, String taskId, Grant grant) {
        if (facilityId == null || facilityId.isBlank()) {
            throw new IllegalArgumentException("facilityId required");
        }
        if (grant == null) {
            throw new IllegalArgumentException("grant required");
        }
        byFacility.put(facilityId, new OpenGrant(facilityId, taskId, grant));
    }

    /** 显式回收某设施的授权；返回是否真撤掉了。 */
    public synchronized boolean release(String facilityId) {
        return byFacility.remove(facilityId) != null;
    }

    /** 该设施当前是否有开着的授权。 */
    public synchronized boolean isOpen(String facilityId) {
        return byFacility.containsKey(facilityId);
    }

    public synchronized List<OpenGrant> openGrants() {
        return List.copyOf(byFacility.values());
    }

    /** 当前开着的全部 {@link Grant}（喂给保护判定）。 */
    public synchronized List<Grant> activeGrants() {
        List<Grant> out = new ArrayList<>();
        for (OpenGrant g : byFacility.values()) out.add(g.grant());
        return out;
    }

    /**
     * 按"当前还在跑的任务"清扫：任务 id 变了、或任务没了，就回收。
     *
     * <p>这是"所有终止路径回收"的机器判据。宿主每 tick（或每次放置请求）调一次，
     * 传入该同伴当前任务的 publicId（没有则 null）。
     *
     * @return 被回收的设施 id 列表（空表示没有需要收的）
     */
    public synchronized List<String> sweep(String currentTaskId) {
        List<String> released = new ArrayList<>();
        var it = byFacility.entrySet().iterator();
        while (it.hasNext()) {
            OpenGrant g = it.next().getValue();
            // taskId 为空 = 授权不绑定具体任务（由调用方显式管理），不在清扫范围内。
            if (g.taskId() == null || g.taskId().isBlank()) {
                continue;
            }
            if (currentTaskId == null || !currentTaskId.equals(g.taskId())) {
                it.remove();
                released.add(g.facilityId());
            }
        }
        return released;
    }

    /** 过期清扫（时间盒授权）。 */
    public synchronized List<String> sweepExpired(long nowEpochMs) {
        List<String> released = new ArrayList<>();
        var it = byFacility.entrySet().iterator();
        while (it.hasNext()) {
            OpenGrant g = it.next().getValue();
            long exp = g.grant().expiresAtEpochMs();
            if (exp > 0 && nowEpochMs > exp) {
                it.remove();
                released.add(g.facilityId());
            }
        }
        return released;
    }

    /** 清空（进程重启时保证无残留）。 */
    public synchronized void clear() {
        byFacility.clear();
    }

    public synchronized int size() {
        return byFacility.size();
    }
}
