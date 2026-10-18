package com.dwinovo.numen.plugins.selfcompile;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P5.3 多 Harness 写锁：给每个"Harness"（验证/部署用的游戏实例或测试世界）一把<b>独占写锁</b>。
 *
 * <p>目的：同一 Harness 同一时刻只允许一个 mutation 写入（避免两个变异互相踩产物/世界状态），
 * 但<b>不同 Harness 之间互不阻塞</b>——于是可以同时跑多个 Harness，各自独占写，互不干扰。
 *
 * <p>语义：
 * <ul>
 *   <li>锁按 {@code harnessId} 分片；同 owner 对同一 Harness <b>可重入</b>（重复获取不增计数）。</li>
 *   <li>不同 owner 争同一 Harness → 后到者失败（fail fast，不排队）。</li>
 *   <li>null harnessId/owner → 一律拒绝（fail closed）。</li>
 *   <li>{@link #release} 只有持有者本人能释放。</li>
 * </ul>
 *
 * <p>纯逻辑、线程安全（{@link ConcurrentHashMap}），无外部依赖，便于单测与之后接入
 * 执行/部署链。
 *
 * <p><b>★ 2026-10-01：本类在生产代码里 0 处引用，当前未接线（只有单测调它）。</b>
 * 它服务于 {@link MutationPipeline}，而那条流水线同样从未接上线。
 * 保留作设计留档；接线前先读 {@link MutationPipeline} 的停用说明——
 * 真正的改码通道是外层 {@code run-mutation.ps1}，两套并存必然漂移。
 */
public final class HarnessWriteLocks {

    private final Map<String, String> holders = new ConcurrentHashMap<>();

    /**
     * 尝试为 {@code harnessId} 获取写锁。
     *
     * @return true = 获取成功（首个持有者或同 owner 重入）；false = 已被他人占用或参数非法
     */
    public boolean tryAcquire(String harnessId, String owner) {
        if (harnessId == null || harnessId.isBlank() || owner == null || owner.isBlank()) return false;
        String previous = holders.putIfAbsent(harnessId, owner);
        return previous == null || previous.equals(owner);   // 同 owner 视为重入
    }

    /**
     * 持有者本人释放写锁。
     *
     * @return true = 确实由其持有并已释放；false = 未持有（或参数非法）
     */
    public boolean release(String harnessId, String owner) {
        if (harnessId == null || owner == null) return false;
        return holders.remove(harnessId, owner);
    }

    /** 当前该 Harness 的持有者（无则空）。 */
    public Optional<String> holderOf(String harnessId) {
        if (harnessId == null) return Optional.empty();
        return Optional.ofNullable(holders.get(harnessId));
    }

    public boolean isLocked(String harnessId) {
        return harnessId != null && holders.containsKey(harnessId);
    }

    /** 当前被占用的 Harness 集合（快照）。 */
    public Set<String> lockedHarnesses() {
        return Set.copyOf(holders.keySet());
    }

    /** 当前被占用 Harness 的数量。 */
    public int activeLocks() {
        return holders.size();
    }
}
