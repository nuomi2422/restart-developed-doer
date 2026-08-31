package com.dwinovo.numen.plugins.selfcompile;

import java.time.Duration;
import java.util.Objects;

/** Orchestrates only the safe pre-compile stages; execution remains disabled. */
public final class MutationPipeline {
    private final MutationWorkspace workspace;
    private final MutationSourceStore sources;
    private final MutationBudget budget;

    public MutationPipeline(MutationWorkspace workspace, int maxAttempts, Duration maxDuration) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.sources = new MutationSourceStore(workspace);
        this.budget = new MutationBudget(maxAttempts, maxDuration);
    }

    public MutationManifest request(String requirement) throws java.io.IOException {
        if (!budget.tryAcquire()) throw new IllegalStateException("mutation budget exhausted");
        return workspace.create(requirement);
    }

    public MutationManifest recordGeneratedSource(MutationManifest manifest,
                                                   String fileName, String source) throws java.io.IOException {
        sources.write(manifest, fileName, source);
        return MutationStateMachine.transition(manifest, MutationState.GENERATED, "");
    }

    /**
     * 编译已生成源码：GENERATED → STATICALLY_CHECKED → COMPILED（自动改码的"编译侧"）。
     * 失败 → 把 javac 输出解析为结构化错误（file:line:col: message）落盘 reports/errors.json，
     * 状态 → FAILED。外部 AI / 生成器按结构化错误精确定位修改源码后重试（recordGeneratedSource → compile）。
     */
    public MutationManifest compile(MutationManifest manifest) throws java.io.IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        MutationManifest checked = MutationStateMachine.transition(manifest, MutationState.STATICALLY_CHECKED, "");
        MutationCompiler.CompileResult result = new MutationCompiler().compile(checked);
        if (result.success()) {
            return MutationStateMachine.transition(checked, MutationState.COMPILED, "");
        }
        java.util.List<MutationErrorParser.CompileError> errors =
                MutationErrorParser.parse(String.join("\n", result.diagnostics()));
        java.nio.file.Path workspaceDir = java.nio.file.Path.of(checked.workspace()).toAbsolutePath().normalize();
        java.nio.file.Path report = workspaceDir.resolve("reports").resolve("errors.json");
        java.nio.file.Files.createDirectories(report.getParent());
        // 结构化错误：每行 file:line:col: message（selfcompile 无 Gson 依赖，纯文本足够外部 AI 定位）
        StringBuilder sb = new StringBuilder();
        for (MutationErrorParser.CompileError e : errors) {
            sb.append(e.file()).append(":").append(e.line()).append(":").append(e.column())
              .append(": ").append(e.message()).append("\n");
        }
        java.nio.file.Files.writeString(report, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        return MutationStateMachine.transition(checked, MutationState.FAILED,
                "compile failed: " + errors.size() + " error(s)");
    }

    /**
     * 证据链验证：CANDIDATE → 全证据满足 → VERIFIED；缺任一证据 → FAILED。
     * 收紧 verdict：不再"有事件就 VERIFIED"，必须 source/classes/jar-hash/部署/MCP/世界对撞全链核实。
     */
    public MutationManifest verify(MutationManifest manifest) throws java.io.IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        MutationVerification.EvidenceReport report = MutationVerification.verify(manifest);
        if (report.verified()) {
            return MutationStateMachine.transition(manifest, MutationState.VERIFIED, "");
        }
        return MutationStateMachine.transition(manifest, MutationState.FAILED,
                "verification missing: " + report.missing());
    }

    public MutationBudget budget() { return budget; }
}
