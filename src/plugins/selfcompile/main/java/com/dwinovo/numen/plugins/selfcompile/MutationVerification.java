package com.dwinovo.numen.plugins.selfcompile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 收紧 verdict：从"有事件就 VERIFIED"升级为<b>完整证据链</b>。
 * 每个阶段对应工作区里一项可核实的证据；全链满足才 {@code VERIFIED}，缺一项即 FAILED。
 *
 * <pre>
 * SOURCE_GENERATED   workspace/source/*.java 存在
 * COMPILED           manifest.state ≥ COMPILED 且 classes 下存在 .class
 * JAR_HASH_RECORDED  workspace/artifacts/*.jar + 同名 .sha256 存在
 * DEPLOYED           workspace/evidence/deployed.json（外部部署脚本写入）
 * MC_LOADED          workspace/evidence/mc_loaded.json（游戏加载后写入）
 * TOOL_REGISTERED    workspace/evidence/tool_registered.json（工具注册后写入）
 * MCP_CALL_SUCCESS   workspace/evidence/mcp_called.json（MCP 真实调用成功后写入）
 * WORLD_EFFECT_VERIFIED workspace/evidence/world_verified.json（世界状态对撞后写入）
 * </pre>
 */
public final class MutationVerification {

    /** 一个证据阶段。 */
    public record EvidenceStage(String name, boolean satisfied, String detail) {}

    /** 一次验证报告：分阶段证据 + 是否全链满足 + 缺失清单。 */
    public record EvidenceReport(List<EvidenceStage> stages, boolean verified, List<String> missing) {}

    private static final List<String> EXTERNAL_EVIDENCE =
            List.of("DEPLOYED", "MC_LOADED", "TOOL_REGISTERED", "MCP_CALL_SUCCESS", "WORLD_EFFECT_VERIFIED");

    private MutationVerification() {}

    /** 核实一个 CANDIDATE 工作区的完整证据链。 */
    public static EvidenceReport verify(MutationManifest manifest) {
        List<EvidenceStage> stages = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Path ws = manifest == null || manifest.workspace() == null
                ? null : Path.of(manifest.workspace()).toAbsolutePath().normalize();

        // 1. 源码生成
        boolean source = ws != null && hasJava(ws.resolve("source"));
        stages.add(new EvidenceStage("SOURCE_GENERATED", source, source ? "source/*.java present" : "missing source"));
        if (!source) missing.add("SOURCE_GENERATED");

        // 2. 编译状态（manifest 至少 COMPILED）
        boolean compiled = manifest != null && manifest.state().ordinal() >= MutationState.COMPILED.ordinal();
        stages.add(new EvidenceStage("COMPILED", compiled, compiled ? "state=" + manifest.state() : "state=" + (manifest == null ? "null" : manifest.state())));
        if (!compiled) missing.add("COMPILED");

        // 3. 编译产物 class
        boolean classes = ws != null && hasClass(ws.resolve("classes"));
        stages.add(new EvidenceStage("CLASSES_PRESENT", classes, classes ? "classes/**/*.class present" : "missing .class"));
        if (!classes) missing.add("CLASSES_PRESENT");

        // 4. jar 产物 + sha256 记录
        boolean jar = ws != null && hasJarAndHash(ws.resolve("artifacts"));
        stages.add(new EvidenceStage("JAR_HASH_RECORDED", jar, jar ? "artifacts/*.jar + .sha256 present" : "missing jar/hash"));
        if (!jar) missing.add("JAR_HASH_RECORDED");

        // 5-9. 外部证据文件（部署/加载/注册/MCP/世界对撞）：文件名 = 阶段名小写
        for (String stage : EXTERNAL_EVIDENCE) {
            String file = stage.toLowerCase();
            boolean ok = ws != null && Files.isRegularFile(ws.resolve("evidence").resolve(file + ".json"));
            stages.add(new EvidenceStage(stage, ok, ok ? file + ".json present" : "missing evidence/" + file + ".json"));
            if (!ok) missing.add(stage);
        }

        return new EvidenceReport(List.copyOf(stages), missing.isEmpty(), List.copyOf(missing));
    }

    private static boolean hasJava(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasClass(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasJarAndHash(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> jars = s.filter(p -> p.getFileName().toString().endsWith(".jar")).toList();
            if (jars.isEmpty()) return false;
            // 每个 jar 都要有对应 .sha256（或统一 hash.json）
            for (Path jar : jars) {
                Path sha = dir.resolve(jar.getFileName() + ".sha256");
                Path hashJson = dir.resolve("hash.json");
                if (!Files.isRegularFile(sha) && !Files.isRegularFile(hashJson)) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
