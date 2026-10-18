package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.core.AcxAliases;
import com.dwinovo.numen.acx.core.AcxArtifactAdopter;
import com.dwinovo.numen.acx.core.AcxBlockCatalog;
import com.dwinovo.numen.acx.core.AcxFacade;
import com.dwinovo.numen.acx.core.AcxHostBridge;
import com.dwinovo.numen.acx.core.AcxLoader;
import com.dwinovo.numen.acx.core.AcxRunner;
import com.dwinovo.numen.acx.core.AcxSessionManager;
import com.dwinovo.numen.acx.core.FileAcxLibrary;
import com.dwinovo.numen.acx.core.JsonlRecordStore;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.entity.NumenPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ACX 插件的 NUMEN 宿主适配层。
 *
 * <p>职责边界：本类只做「装配」——把 Numen 工具桥接成 ACX 端口、把 jar 里的
 * stable 脚本铺到 config、建执行器/会话/版本库/门面，然后注册 8 个 {@code acx_*}
 * 门面工具。执行语义全在 {@code acx-core}（纯 JVM、离线 153 项测试）。</p>
 *
 * <p><b>惰性初始化</b>：插件 setup 早于 {@code NumenCore.registerTools()} 全量注册，
 * 不能在构造期桥接。首次任一 {@code acx_*} 调用时 {@link #ensureReady()} 一次成型。</p>
 *
 * <p>落盘：{@code config/numen/acx/{records.jsonl, library.json, lib/stable/*.ac}}，
 * 与旧 AC 的 {@code config/numen/ac-library.json} 刻意分开，防互相清库。</p>
 */
public final class AcxPlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(AcxPlugin.class);

    /** jar 内脚本目录（前缀，与 resources/acx-lib/stable 同步）。 */
    private static final String RES_PREFIX = "/acx-lib/stable/";
    private static final String RES_DIR = "acx-lib/stable/";

    /**
     * 测试夹具类内置脚本：随 jar 发布（离线测试 {@code AcxTestMain} 直接读
     * {@code resources/acx-lib}，逐条断言它们在），但<b>不在生产里自动铺盘、自动上线</b>。
     *
     * <p>为什么要有这张表（2026-10-08）：这些 demo/自证脚本对游戏内 AI 只是噪音 ——
     * 它们随 jar 自动进 config、自动 approve，于是 {@code acx_library} 里一堆用不上的样例。
     * 但直接删文件会红掉离线测试（它们是回归夹具）。所以：<b>文件保留，生产跳过</b>。
     * 真要跑它们，显式点名执行即可（loader 仍能按名解析 jar 副本）—— 只是不再自动上线。</p>
     */
    private static final java.util.Set<String> HIDDEN_BUNDLED = java.util.Set.of(
            "do_while_scan", "hp_guard_goto", "ignore_failure_demo", "inventory_if",
            "timeout_demo", "numen_smoke_read_move_read", "subac_nesting",
            "ore_scan_inspect", "ore_goto_mine", "mine_nearest_ore");

    private static boolean isHiddenBundled(String acName) {
        return HIDDEN_BUNDLED.contains(acName);
    }

    /**
     * jar 内置脚本清单，<b>从 jar 现场枚举</b>，不再硬编码。
     *
     * <p>为什么改：硬编码名单在真机踩过——往 jar 里加了
     * {@code mine_nearest_ore.ac}、jar 也确实打进去了，但名单没同步，
     * 脚本既没铺到 config 也没进版本库，表现为「按名执行说库里没有」。
     * 加脚本必须只改 resources 目录、不改 Java。</p>
     */
    /** jar 内脚本清单的缓存：枚举一次就够，别每步重算（原来日志被同样的 warn 刷了几十行）。 */
    private static volatile java.util.List<String> BUNDLED_CACHE;

    private static List<String> bundledScripts() {
        java.util.List<String> cached = BUNDLED_CACHE;
        if (cached != null) {
            return cached;
        }
        List<String> names = new ArrayList<>();
        // ① classloader 资源枚举。不能按协议分派：NeoForge 的 union 类加载器给的是
        //    union:/E:/…/mods/xxx.jar（没有 !/ 段），普通 jar 类加载器给 jar:file:/…!/acx-lib/stable，
        //    dev 环境给 file:…/acx-lib/stable。统一走 jarFromUrl() 剥协议再判断。
        try {
            java.util.Enumeration<java.net.URL> urls =
                    AcxPlugin.class.getClassLoader().getResources(RES_DIR);
            while (urls.hasMoreElements()) {
                java.net.URL u = urls.nextElement();
                try {
                    if ("file".equals(u.getProtocol())) {
                        Path dir = Path.of(u.toURI());
                        if (Files.isDirectory(dir)) {
                            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                                s.map(f -> f.getFileName().toString())
                                        .filter(n -> n.endsWith(".ac"))
                                        .sorted()
                                        .forEach(names::add);
                            }
                        } else {
                            names.addAll(acNamesInJar(dir));
                        }
                    } else {
                        Path jar = jarFromUrl(u);
                        if (jar != null) {
                            names.addAll(acNamesInJar(jar));
                        } else {
                            LOG.warn("[acx] 看不懂的资源 URL（协议 {}，剥完不像 jar）: {}", u.getProtocol(), u);
                        }
                    }
                } catch (Exception inner) {
                    LOG.warn("[acx] 资源 {} 枚举失败: {}", u, inner.toString());
                }
            }
        } catch (Exception e) {
            LOG.warn("[acx] classloader 资源枚举失败: {}", e.toString());
        }
        // ② 兜底：code source。NeoForge 下是 union:/…，所以同样走 jarFromUrl，并把 URL 打进日志便于诊断
        if (names.isEmpty()) {
            try {
                java.net.URL loc = AcxPlugin.class.getProtectionDomain().getCodeSource().getLocation();
                LOG.info("[acx] code source = {}", loc);
                Path jar = jarFromUrl(loc);
                if (jar != null) {
                    names.addAll(acNamesInJar(jar));
                } else {
                    LOG.warn("[acx] code source 剥协议后不是常规 jar: {}", loc);
                }
            } catch (Exception e) {
                LOG.warn("[acx] code source 兜底失败: {}", e.toString());
            }
        }
        // ③ 再兜底：classloader 容器根（union:/…/x.jar 或 jar:file:/…!/ 或 file:…/）
        if (names.isEmpty()) {
            try {
                java.net.URL root = AcxPlugin.class.getResource("/");
                LOG.info("[acx] classloader root = {}", root);
                if (root != null) {
                    Path jar = jarFromUrl(root);
                    if (jar != null) {
                        names.addAll(acNamesInJar(jar));
                    }
                }
            } catch (Exception e) {
                LOG.warn("[acx] classloader root 兜底失败: {}", e.toString());
            }
        }
        if (names.isEmpty()) {
            LOG.warn("[acx] ★ 枚举不出 jar 内置脚本（内置脚本不会自动上线）："
                    + "往 resources/acx-lib/stable/ 放 .ac 后请检查这行日志");
        }
        java.util.List<String> deduped = names.stream().distinct().sorted().collect(java.util.stream.Collectors.toList());
        BUNDLED_CACHE = deduped;
        return deduped;
    }

    /**
     * 把任意协议的资源 URL 还原成本地 jar 路径；不是常规 .jar 文件就返回 {@code null}。
     *
     * <p>为什么要这么写（真机踩过两轮）：jar 里是 {@code jar:file:/E:/…/x.jar!/acx-lib/stable}，
     * 而 NeoForge 的 union 类加载器给的是 {@code union:/E:/…/mods/x.jar}（没有 {@code !/} 段、
     * 空格被 URL 编码成 {@code %20}）。所以顺序固定是：剥协议头 → URL 解码 → 砍 {@code #} 补丁标记 → 剥 {@code !/} 之后
     * → 剥前导斜杠 → 必须以 .jar 结尾且 isRegularFile。</p>
     */
    private static Path jarFromUrl(java.net.URL u) {
        String spec = u.toString();
        int colon = spec.indexOf(':');
        if (colon > 0) {
            spec = spec.substring(colon + 1);
        }
        try {
            spec = java.net.URLDecoder.decode(spec, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // 解码失败就按原样试
        }
        // NeoForge 的 union 类加载器会在 jar 名后面插 %23<数字>!（解出来形如 "xxx.jar#240"），
        // 必须在判 .jar 之前砍掉这个补丁标记，否则永远认不出 jar ——真机日志：
        // union:/E:/.../mods/numen-plugin-acx-...jar%23240!/acx-lib/stable
        int hash = spec.indexOf('#');
        if (hash >= 0) {
            spec = spec.substring(0, hash);
        }
        int bang = spec.indexOf("!/");
        if (bang >= 0) {
            spec = spec.substring(0, bang);
        }
        while (spec.startsWith("/")) {
            spec = spec.substring(1);
        }
        if (!spec.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            return null;
        }
        try {
            Path path = Path.of(spec);
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 列出 jar 里 {@code acx-lib/stable/*.ac} 的文件名。 */
    private static List<String> acNamesInJar(Path jar) throws IOException {
        List<String> out = new ArrayList<>();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            jf.stream()
                    .map(java.util.jar.JarEntry::getName)
                    .filter(n -> n.startsWith(RES_DIR) && n.endsWith(".ac"))
                    .map(n -> n.substring(RES_DIR.length()))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    private NumenApi api;
    private AcxHostGate gate;
    private AcxRegistry registry;
    private AcxRunner runner;
    private AcxSessionManager sessions;
    private AcxFacade facade;
    private JsonlRecordStore store;
    private ExecutorService executor;
    private volatile NumenPlayer companion;
    private volatile boolean ready;
    private String readySummary = "尚未初始化";
    /** B6/S2：学习者 AC 草稿的采纳口（只 publish 成 GENERATED，永不 approve）。 */
    private volatile AcxArtifactAdopter artifactAdopter;

    /**
     * 当前积木清单文本（{@link #writeBlockCatalog} 写出时一并留在这里）。
     *
     * <p>留一份的理由：{@code acx_blocks} 工具与 {@code blocks.txt} 文件必须给 AI
     * <b>完全一样</b>的内容 —— 学习者读文件、游戏内 AI 调工具，两条路漂了就会出现
     * 「一边说这个积木能用、另一边说没有」这种最难查的不一致。
     */
    private volatile String blockCatalogText;

    @Override
    public void setup(NumenApi numen) {
        this.api = numen;
        this.gate = new AcxHostGate();
        numen.registerTool(new AcxFacadeTools.Execute(this));
        numen.registerTool(new AcxFacadeTools.Status(this));
        numen.registerTool(new AcxFacadeTools.Resume(this));
        numen.registerTool(new AcxFacadeTools.Cancel(this));
        numen.registerTool(new AcxFacadeTools.Publish(this));
        numen.registerTool(new AcxFacadeTools.Approve(this));
        numen.registerTool(new AcxFacadeTools.Rollback(this));
        numen.registerTool(new AcxFacadeTools.Library(this));
        // 第 9 个门面（2026-10-06）：列出当前真的可用的积木。
        // 干活 AI 此前写 AC 只能猜 block 名（提示词示例里还写着不存在的 "move"），
        // 连拒之后学会交只读空壳 —— 给一个能问的口子比让它猜便宜得多。
        numen.registerTool(new AcxFacadeTools.Blocks(this));
        // 再加一个（B6/S2）：采纳学习者 AC 草稿。默认路径已在 ensureReady 跑过一次，
        // 这里给「立刻问出草稿有没有被接住」的口子 —— 否则「投递了但没被接」没有观测面。
        numen.registerTool(new AcxAdoptDraftsTool(this));
        LOG.info("[acx] 9 个门面 + acx_adopt_drafts 已注册（惰性初始化：首次调用时桥接工具并加载脚本）");
    }

    /** 幂等惰性初始化；失败会抛出，由门面壳转成失败回执。 */
    public synchronized void ensureReady() {
        if (ready) {
            return;
        }
        Path base = api.configDir().resolve("acx");
        try {
            Files.createDirectories(base);
            Path libRoot = base.resolve("lib");
            materializeStable(libRoot);

            registry = new AcxRegistry();

            // 惰性桥接：此刻 NumenCore 全量工具已注册（首次 acx_* 调用必然晚于引擎 init）
            List<AcxToolPort> ports = new ArrayList<>();
            for (NumenTool tool : ToolRegistry.all()) {
                if (tool == null) {
                    // 防御：外部 MCP 客户端连接失败等来源可能把 null 塞进注册表，
                    // 一个 null 会让整块 ACX 初始化 NPE（2026-10-08 实机）。
                    continue;
                }
                String name = tool.name();
                if (name == null || name.isBlank() || name.startsWith("acx_")) {
                    continue;
                }
                ports.add(new NumenToolBridgeX(tool, this));
            }
            AcxHostBridge.Report bridge = AcxHostBridge.registerAll(
                    registry, ports, NumenToolBridgeX.probeFor(this::currentCompanion));

            // ★ 2026-10-06：把「真的注册了哪些积木」写成文件，供跨插件的 AI 照抄。
            //
            // 为什么必须自动化：学习者（plugins/learner）与 acx 之间不能 import
            // （numen-plugin.gradle:37-44 封死），于是它此前**没有任何积木清单**，
            // 只能照抄提示词里那个手写示例 —— 而那个示例写着不存在的 block "move"。
            // 后果实测：连拒三次后 AI 学会交「最小可解析空壳」（只读状态、零动作），
            // 却真的被执行、真的报 SUCCESS。
            // 跨插件沿用既有做法：投料方写文件、消费方只读（见 63 号 §3）。
            writeBlockCatalog(base, ports, bridge);

            AcxLoader.LoadReport load = AcxLoader.loadAll(libRoot, registry, false, true);
            for (String err : load.errors()) {
                LOG.warn("[acx] 脚本加载错误（已隔离）: {}", err);
            }
            for (String warn : load.warnings()) {
                LOG.warn("[acx] 脚本加载告警: {}", warn);
            }

            store = new JsonlRecordStore(base.resolve("records.jsonl"));
            FileAcxLibrary library = new FileAcxLibrary(base.resolve("library.json"), registry);
            library.load();
            autoApproveBundled(library, load);

            // ★ B6/S2：把学习者产出的 AC 草稿接进来（此前 Verdict.acScriptDraft 只进回执，无人消费）。
            //   采纳 = publish 成 GENERATED，**不 approve** —— 上线仍需人工 acx_approve，
            //   与 63 §8「游戏内可以写 AC，发布要过闸」一致。
            //   放在 autoApproveBundled 之后：jar 内置脚本的自动批准只认 jar 里那份，
            //   学习者草稿绝不能借那条路自动上线。
            Set<String> known = new HashSet<>(load.registered().keySet());
            known.addAll(library.names());
            artifactAdopter = new AcxArtifactAdopter(api.configDir(), library, known);
            AcxArtifactAdopter.Report adopted;
            try {
                adopted = artifactAdopter.adoptAll();
                if (adopted.adopted() > 0 || adopted.rejected() > 0) {
                    LOG.info("[acx] 学习者 AC 草稿采纳: 采纳 " + adopted.adopted()
                            + " 拒收 " + adopted.rejected() + " 待处理 " + adopted.scanned()
                            + "（GENERATED，需人工 acx_approve 才上线）");
                }
            } catch (RuntimeException e) {
                // 采纳失败不许拖垮 acx 初始化：草稿还在投递箱里，下次启动或手工调工具再试
                LOG.warn("[acx] 学习者 AC 草稿采纳异常（不影响 acx 本身）: " + e.getMessage());
            }

            runner = AcxRunner.builder()
                    .tools(registry)
                    // ★ 子 AC 委托靠它：没有 catalog，step.block 写成别的 AC 名就会「找不到积木或 AC」。
                    //   真机实测踩过（subac_nesting FAIL），loader 那时明明已经加载成功了。
                    .catalog(load.catalog())
                    .events(AcxMonitor::publish)
                    .store(store)
                    .build();
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "numen-acx-runner");
                t.setDaemon(true);
                return t;
            });
            sessions = new AcxSessionManager(runner, executor);
            facade = new AcxFacade(sessions, library::active, library::version, registry, library,
                    // ★ 单一真源：acx_blocks 与 blocks.txt 用**同一段文本**（学习者读文件、
                    //   游戏内 AI 调工具，两条路不可能漂）。
                    () -> blockCatalogText);

            ready = true;
            readySummary = "桥接工具 " + bridge.registered().size()
                    + " 别名 " + bridge.aliasNames().size()
                    + " 跳过 " + bridge.skipped().size()
                    + "；脚本注册 " + load.registered().size()
                    + "（beta " + load.betaOnly().size() + "）"
                    + " 错误 " + load.errors().size()
                    + " 警告 " + load.warnings().size();
            LOG.info("[acx] ready: {}", readySummary);
        } catch (IOException e) {
            // ★ 顺手把堆栈打进日志：工具回执只带 getMessage()，而这里的消息会被截断，
            //   实测「Index 75 out of bounds for length 75」就是**定位不到**的那种失败。
            //   失败要能定位，否则每次都得重新构建一轮才能猜。
            LOG.error("[acx] 初始化 IO 失败（堆栈见此）", e);
            throw new IllegalStateException("ACX 初始化 IO 失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            LOG.error("[acx] 初始化失败（堆栈见此）", e);
            throw new IllegalStateException("ACX 初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * jar 自带脚本自动进版本库并上线（AC-B6）。
     *
     * <p>策略：<b>只对「版本库里从没见过的 jar 自带脚本」</b>做 publish + approve。
     * 理由：随 jar 发布的脚本等价于随插件版本发布的代码，天然已审；自动批准省掉每次发版
     * 都要人点一次。但玩家/学习者在游戏里改过的脚本<b>不</b>自动批准 —— 它已经进过库，
     * 走 GENERATED/PENDING，必须 {@code acx_approve} 人工放行，人工批准那道闸才不形同虚设。
     * 用户自己放进 config 的 .ac 不在 jar 内置脚本名单 名单里，同样不自动批准。</p>
     */
    /**
     * 把当前注册表里的积木渲染成 {@code <config>/acx/blocks.txt}，供跨插件的 AI 只读照抄。
     *
     * <p><b>为什么是文件而不是接口</b>：{@code numen-plugin.gradle:37-44} 把跨插件 import
     * 在编译期封死，{@code plugins/learner} 看不见 {@code plugins/acx} 的任何类。
     * 既有先例就是「投料方写文件 + 消费方只读」（见 63 号 §3）。
     *
     * <p><b>写失败不许拖垮 acx</b>：它只是参考资料，执行链不依赖它 —— 但必须留日志，
     * 否则「AI 看不到清单」会退化成查不到根因的哑故障。
     */
    private void writeBlockCatalog(Path base, List<AcxToolPort> ports, AcxHostBridge.Report bridge) {
        try {
            String text = AcxBlockCatalog.render(ports, AcxAliases.all());
            blockCatalogText = text;
            Path f = base.resolve("blocks.txt");
            Path tmp = base.resolve("blocks.txt.tmp");
            Files.writeString(tmp, text, java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("[acx] 积木清单已写出（{} 真名 / {} 别名）: {}",
                    bridge.registered().size(), bridge.aliasNames().size(), f);
        } catch (Exception e) {
            LOG.warn("[acx] 积木清单写出失败（不影响执行，但 AI 会看不到可用积木）: {}", e.toString());
        }
    }

    private void autoApproveBundled(FileAcxLibrary library, AcxLoader.LoadReport load) {
        List<String> approved = new ArrayList<>();
        for (AcxDefinition def : load.registered().values()) {
            if (!isBundled(def.name()) || isHiddenBundled(def.name())) {
                continue;
            }
            // 判据是「库里的生效版本是不是还等于 jar 里这份」，不是「库里有这个名字没有」。
            // 只判名字的话，插件升版后内置脚本改了内容、库里旧定义还在，就会一直跑旧版本
            // （真机踩过：timeout_demo 换了循环体，跑起来还是旧的 set_timer 版本）。
            if (library.hasAnyVersion(def.name())) {
                AcxDefinition online = null;
                try {
                    online = library.active(def.name());
                } catch (RuntimeException ignored) {
                    // 没上线 / active 状态异常 → 视为需要更新
                }
                if (online != null
                        && com.dwinovo.numen.acx.core.AcxFingerprint.of(online)
                                .equals(com.dwinovo.numen.acx.core.AcxFingerprint.of(def))) {
                    continue;
                }
            }
            try {
                // 子 AC 委托要过静态校验，得把同批已加载的 AC 名交给版本库
                String version = library.publish(def, "jar 内置脚本，随插件版本发布",
                        new java.util.HashSet<>(load.registered().keySet()));
                library.approve(def.name(), version, "acx-builtin",
                        "随 jar 发布视为已审；此后任何修改都需人工 acx_approve");
                approved.add(def.name() + "@" + version);
            } catch (RuntimeException e) {
                LOG.warn("[acx] 内置脚本未能上线（不影响其余）: {} -> {}", def.name(), e.getMessage());
            }
        }
        if (!approved.isEmpty()) {
            library.persist();
            LOG.info("[acx] 内置脚本已上线（发布即已审）: {}", approved);
        }
    }

    private static boolean isBundled(String acName) {
        for (String file : bundledScripts()) {
            if (file.equals(acName + ".ac")) {
                return true;
            }
        }
        return false;
    }

    /** jar 内 stable 脚本铺到 config；已存在不覆盖（游戏内修订优先）。 */
    private void materializeStable(Path libRoot) throws IOException {
        Path stable = libRoot.resolve("stable");
        Files.createDirectories(stable);
        List<String> bundledNames = bundledScripts();
        LOG.info("[acx] jar 内置脚本 {} 个: {}", bundledNames.size(), bundledNames);
        for (String name : bundledNames) {
            String bare = name.endsWith(".ac") ? name.substring(0, name.length() - 3) : name;
            if (isHiddenBundled(bare)) {
                // 测试夹具：留在 jar（离线测试读 resources），但不进 config → 生产不可见。
                continue;
            }
            try (InputStream in = AcxPlugin.class.getResourceAsStream(RES_PREFIX + name)) {
                if (in == null) {
                    LOG.warn("[acx] jar 内脚本缺失: /acx-lib/stable/{}", name);
                    continue;
                }
                byte[] bundled = in.readAllBytes();
                Path target = stable.resolve(name);
                if (Files.exists(target)) {
                    byte[] onDisk = Files.readAllBytes(target);
                    if (java.util.Arrays.equals(bundled, onDisk)) {
                        continue;
                    }
                    // jar 拥有的脚本：内容变了说明插件升版了。lib/stable 是 jar 的物料区，
                    // 玩家要改自己的 AC 应该走 acx_publish 进版本库，所以这里以 jar 为准，
                    // 旧内容留一份 .stale-<ts> 存档而不是静默覆盖。
                    Path stale = stable.resolve(name + ".stale-" + System.currentTimeMillis());
                    Files.move(target, stale, StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("[acx] 内置脚本随插件升版已更新（旧内容存档 {}）: {}", stale.getFileName(), name);
                }
                Files.write(target, bundled);
            }
        }
        // 不在 jar 名单里的 .ac 一律当玩家脚本处理（只提醒，不动它）
        try (java.util.stream.Stream<Path> files = Files.list(stable)) {
            files.filter(p -> p.toString().endsWith(".ac"))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !isBundledFile(n))
                    .forEach(n -> LOG.warn("[acx] lib/stable/{} 不在 jar 内置名单，按玩家脚本处理", n));
        }
    }

    private static boolean isBundledFile(String fileName) {
        return bundledScripts().contains(fileName);
    }

    // ── 门面壳用的访问口 ────────────────────────────────────────────────

    /**
     * B6/S2：再跑一次学习者 AC 草稿采纳（工具入口用）。
     *
     * <p>必须先 {@link #ensureReady()}：采纳口是在初始化里连同版本库一起建的，
     * 没 ready 就调等于对着空引用操作。
     */
    public Map<String, Object> adoptLearnerDrafts() {
        ensureReady();
        AcxArtifactAdopter adopter = artifactAdopter;
        if (adopter == null) {
            // 走到这里说明初始化路径变了（不是「没有草稿」）—— 两者含义不同，必须能分辨
            throw new IllegalStateException("ACX 已初始化但没有草稿采纳口：artifactAdopter 为 null");
        }
        return adopter.adoptAll().toMap();
    }

    /** 每个 acx_* 调用入口先绑一次当前同伴（桥接派发要用）。 */
    public void bindCompanion(NumenPlayer player) {
        if (player != null) {
            this.companion = player;
        }
    }

    public NumenPlayer currentCompanion() {
        return companion;
    }

    public AcxHostGate gate() {
        return gate;
    }

    public AcxFacade facade() {
        ensureReady();
        return facade;
    }

    /** 供调试/观测的初始化摘要。 */
    public String readySummary() {
        return readySummary;
    }
}
