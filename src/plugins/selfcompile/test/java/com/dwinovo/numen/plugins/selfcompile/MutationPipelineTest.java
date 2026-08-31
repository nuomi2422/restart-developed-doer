package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MutationPipelineTest {

    @Test
    void compileSuccessTransitionsToCompiled() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-pipe-ok-");
        MutationPipeline pipeline = new MutationPipeline(new MutationWorkspace(root), 3, Duration.ofSeconds(60));
        MutationManifest manifest = pipeline.request("tool");
        manifest = pipeline.recordGeneratedSource(manifest, "Tool.java",
                "package com.dwinovo.numen.plugins.selfcompile.generated;\npublic final class Tool {}\n");
        MutationManifest compiled = pipeline.compile(manifest);
        assertEquals(MutationState.COMPILED, compiled.state());
    }

    @Test
    void compileFailureTransitionsToFailedAndWritesStructuredErrors() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-pipe-bad-");
        MutationPipeline pipeline = new MutationPipeline(new MutationWorkspace(root), 3, Duration.ofSeconds(60));
        MutationManifest manifest = pipeline.request("broken tool");
        manifest = pipeline.recordGeneratedSource(manifest, "Broken.java",
                "package com.dwinovo.numen.plugins.selfcompile.generated;\npublic final class Broken {\n");
        MutationManifest failed = pipeline.compile(manifest);
        assertEquals(MutationState.FAILED, failed.state());
        // 结构化错误落盘（外部 AI 据此精确改码后重试）
        Path errFile = Path.of(failed.workspace(), "reports", "errors.json");
        assertTrue(Files.exists(errFile), "errors.json should exist");
        String content = Files.readString(errFile);
        assertFalse(content.isBlank(), "errors.json should not be blank: " + content);
    }
}
