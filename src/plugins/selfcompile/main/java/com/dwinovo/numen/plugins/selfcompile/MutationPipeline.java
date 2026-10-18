package com.dwinovo.numen.plugins.selfcompile;

import java.time.Duration;
import java.util.Objects;

/**
 * Orchestrates only the safe pre-compile stages; execution remains disabled.
 *
 * <h2>★ 2026-10-01 已停用：整条变异流水线在游戏里从未接线</h2>
 *
 * <p><b>本类及其下游（{@link MutationCompiler} / {@link MutationVerification} /
 * {@link MutationBudget} / {@link MutationStaticChecker} / {@link HarnessWriteLocks} /
 * {@link MutationArtifactStore}）在生产代码里 0 处引用</b>——只有单测能调到它们。
 * 换句话说：<b>它已经关着了</b>，不是"正在跑但有风险"，而是<b>根本没接上线</b>。
 *
 * <p><b>为什么看起来像活的：</b>它有完整设计、11 态状态机、48 个通过的测试，
 * 文档也把它当"自变异系统"讲。代码质量与可达性是两回事。
 *
 * <p><b>现在真正在跑的是什么：</b>
 * <ul>
 *   <li><b>游戏内</b>只剩一个"需求登记口"：{@code selfcompile_request} 写审计目录 +
 *       {@code selfcompile_status} 查状态。<b>不生成代码、不编译、不部署。</b>
 *       它的角色是<b>进游戏监督 AI 的小流程</b>——让游戏里的 AI 把需求落到盘上，
 *       供工程流读取。</li>
 *   <li><b>改码</b>已整个搬进工程流（stager）②编码实现阶段 + 外层
 *       {@code rdd-selfcompile/scripts/run-mutation.ps1}（唯一改码入口）。
 *       那条链自己用 {@code new-mutation.ps1} 造目录 id，
 *       <b>与本插件的 {@code MutationWorkspace} 是两个不相干的命名空间</b>。</li>
 * </ul>
 *
 * <p><b>处置：暂时保留但标注停用，不要接线。</b>设计本身（11 态 + 预算止损 +
 * 结构化编译错误 + 全链证据核验）仍值得留档，将来若要把自编译搬回游戏内再启用。
 * 若误把它当"当前改码通道"去接线，会造出<b>第二套真相源</b>——
 * 外层已有一套 MutationId 与门禁，两套并存必然漂移。
 *
 * <p>删除前先确认 {@code 30-主要功能不回退清单.md} 没有依赖它。
 * 真正的改码通道见 {@code AGENTS.md}「唯一改码入口」。
 */
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

    /**
     * P5 提案：只登记需求 + 建隔离工作区（PROPOSED），<b>不占执行预算、不生成代码</b>。
     * 审批通过（{@link #approve}）才转入执行链；否则 {@link #reject} 归档。
     */
    public MutationManifest propose(String requirement) throws java.io.IOException {
        return workspace.propose(requirement);
    }

    /** 审批通过：PROPOSED → WORKSPACE_CREATED，并在此时占用一次执行预算（提案阶段不占）。 */
    public MutationManifest approve(MutationManifest manifest) throws java.io.IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        if (!budget.tryAcquire()) throw new IllegalStateException("mutation budget exhausted");
        return MutationStateMachine.transition(manifest, MutationState.WORKSPACE_CREATED, "");
    }

    /** 审批否决：PROPOSED → ARCHIVED（终态留档，日后可重开），不占预算。 */
    public MutationManifest reject(MutationManifest manifest, String reason) throws java.io.IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        return MutationStateMachine.transition(manifest, MutationState.ARCHIVED,
                reason == null || reason.isBlank() ? "proposal rejected" : reason);
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
