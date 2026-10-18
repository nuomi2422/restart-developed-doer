package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.plugins.worldstate.provider.EntityCountWorldProvider;
import com.dwinovo.numen.plugins.worldstate.provider.EnvironmentWorldProvider;
import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.json.ObservationJson;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.provider.ProviderRegistry;
import com.dwinovo.numen.worldstate.core.query.QueryService;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshService;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * 世界观测的宿主侧真源：装配存储、采集器、查询服务，并把观测持久化到
 * {@code config/numen/worldstate/observations.json}（按世界存档命名空间隔离）。
 *
 * <p><b>同步采集</b>：{@code read}/{@code refresh} 由工具在服务端线程调用，采集器直接读真实世界，
 * 线程安全。写盘用 tmp + 原子替换；重启后载入的观测一律标历史（见 {@link ObservationJson}）。
 *
 * <p>共享方式沿用 settlement 的先例：{@link WorldStateMod#service()} 给别的模块一个只读入口，
 * 不必 import 整份实现。
 */
public final class WorldStateService {

    private static final Logger LOG = LoggerFactory.getLogger("worldstate");

    private final Path file;
    private final InMemoryObservationStore store;
    private final ProviderRegistry providers = new ProviderRegistry();
    private final TopicFreshnessPolicy freshness = TopicFreshnessPolicy.defaults();
    private final RefreshService refreshService;
    private final QueryService queryService;

    public WorldStateService(Path configDir) {
        this.file = configDir.resolve("worldstate").resolve("observations.json");
        this.store = new InMemoryObservationStore(512);
        this.providers.register(new EnvironmentWorldProvider());
        this.providers.register(new EntityCountWorldProvider());
        this.refreshService = new RefreshService(providers, store, Runnable::run);
        this.queryService = new QueryService(store, freshness, refreshService, System::currentTimeMillis);
        load();
    }

    public Path file() {
        return file;
    }

    public ProviderRegistry providers() {
        return providers;
    }

    /** 已接入采集器的主题。未列入的主题查询会明确返回 NO_PROVIDER。 */
    public Set<com.dwinovo.numen.worldstate.core.model.Topic> topics() {
        return providers.topics();
    }

    public int size() {
        return store.size();
    }

    /** 只读已有观测（不扫描世界）。 */
    public ReadResult read(MinecraftServer server, ObservationRequest request) {
        return queryService.read(worldId(server), request);
    }

    /** 主动采集并写入观测；成功才落盘。 */
    public RefreshResult refresh(MinecraftServer server, ObservationRequest request) {
        RefreshResult result = queryService.refresh(context(server), request);
        if (result.success()) {
            persist();
        }
        return result;
    }

    private McCollectionContext context(MinecraftServer server) {
        return new McCollectionContext(server, worldId(server),
                server == null ? 0L : server.getTickCount(), System.currentTimeMillis());
    }

    /** 世界存档标识——观测命名空间的一部分，两个存档不能互相串数据。 */
    static String worldId(MinecraftServer server) {
        if (server == null) return "unknown";
        try {
            String name = server.getWorldData().getLevelName();
            if (name != null && !name.isBlank()) return name;
        } catch (RuntimeException ignored) {
            // 拿不到名字也要有一个稳定命名空间，回退到 unknown，而不是崩。
        }
        return "unknown";
    }

    private void load() {
        try {
            if (Files.isRegularFile(file)) {
                for (Observation o : ObservationJson.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8))) {
                    store.put(o);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 坏掉的观测缓存不拖垮插件启动（与 SettlementService / RddAssetStore 同纪律）。
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, ObservationJson.toJson(store.all()), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 落盘失败只记事件与日志——本次内存观测仍有效，不把成功的刷新变成崩溃。
            LOG.warn("[worldstate] persist failed: {}", e.toString());
            WorldStateMonitor.publish("persist_failed", java.util.Map.of("error", String.valueOf(e)));
        }
    }
}
