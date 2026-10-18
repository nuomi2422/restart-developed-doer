package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** P5.1 Proposal 流水线：提案 → 审批 → 进执行链 / 否决 → 归档。 */
class MutationProposalTest {

    @Test
    void proposalStartsProposedAndApprovesIntoPipeline() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-proposal-ok-");
        MutationPipeline pipeline = new MutationPipeline(new MutationWorkspace(root), 3, Duration.ofSeconds(60));

        MutationManifest proposed = pipeline.propose("add a whereami tool");
        assertEquals(MutationState.PROPOSED, proposed.state(), "提案初始应为 PROPOSED");

        MutationManifest approved = pipeline.approve(proposed);
        assertEquals(MutationState.WORKSPACE_CREATED, approved.state(), "审批通过应转入执行链");

        // 审批后的提案能继续走正常流水线（生成源码）
        MutationManifest generated = pipeline.recordGeneratedSource(approved, "Tool.java",
                "package com.dwinovo.numen.plugins.selfcompile.generated;\npublic final class Tool {}\n");
        assertEquals(MutationState.GENERATED, generated.state());
    }

    @Test
    void rejectedProposalIsArchivedAndCannotEnterExecution() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-proposal-no-");
        MutationPipeline pipeline = new MutationPipeline(new MutationWorkspace(root), 3, Duration.ofSeconds(60));

        MutationManifest proposed = pipeline.propose("risky rewrite");
        MutationManifest rejected = pipeline.reject(proposed, "not worth the risk");
        assertEquals(MutationState.ARCHIVED, rejected.state(), "否决应归档");
        assertFalse(MutationStateMachine.canTransition(rejected.state(), MutationState.GENERATED),
                "归档的提案不得直接进入生成/执行");
        assertFalse(MutationStateMachine.canTransition(rejected.state(), MutationState.VERIFIED));
    }

    @Test
    void proposalApprovalConsumesBudgetButProposalItselfDoesNot() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-proposal-budget-");
        MutationPipeline pipeline = new MutationPipeline(new MutationWorkspace(root), 1, Duration.ofSeconds(60));

        MutationManifest proposed = pipeline.propose("gated change");
        assertEquals(0, pipeline.budget().attempts(), "提案阶段不占预算");

        pipeline.approve(proposed);
        assertEquals(1, pipeline.budget().attempts(), "审批才占一次预算");

        // 预算已用尽，再审批一个提案应被拒
        MutationManifest second = pipeline.propose("another");
        assertThrows(IllegalStateException.class, () -> pipeline.approve(second));
    }

    @Test
    void stateMachineGatesProposalEdges() {
        assertTrue(MutationStateMachine.canTransition(MutationState.PROPOSED, MutationState.WORKSPACE_CREATED));
        assertTrue(MutationStateMachine.canTransition(MutationState.PROPOSED, MutationState.REQUESTED));
        assertTrue(MutationStateMachine.canTransition(MutationState.PROPOSED, MutationState.ARCHIVED));
        assertFalse(MutationStateMachine.canTransition(MutationState.PROPOSED, MutationState.VERIFIED));
        assertFalse(MutationStateMachine.canTransition(MutationState.PROPOSED, MutationState.DELIVERED));
        assertFalse(MutationStateMachine.canTransition(MutationState.PROPOSED, MutationState.COMPILED));
    }
}
