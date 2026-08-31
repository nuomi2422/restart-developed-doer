package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MutationVerificationTest {

    @Test
    void fullEvidenceChainVerifies() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-verify-ok-");
        write(root, "source/Tool.java", "package x;\npublic final class Tool {}\n");
        write(root, "classes/com/x/Tool.class", new byte[]{0, 1, 2});
        write(root, "artifacts/tool.jar", new byte[]{1, 2, 3});
        write(root, "artifacts/tool.jar.sha256", "abc123");
        for (String ev : List.of("deployed", "mc_loaded", "tool_registered", "mcp_call_success", "world_effect_verified")) {
            write(root, "evidence/" + ev + ".json", "{}");
        }
        MutationManifest m = new MutationManifest("m1", "req", MutationState.CANDIDATE, 1,
                Instant.now(), root.toString(), null, null);
        MutationVerification.EvidenceReport report = MutationVerification.verify(m);
        assertTrue(report.verified(), "full chain should verify; missing=" + report.missing());
        assertEquals(9, report.stages().size());
    }

    @Test
    void missingEvidenceFailsWithList() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-verify-bad-");
        write(root, "source/Tool.java", "package x;\npublic final class Tool {}\n");
        MutationManifest m = new MutationManifest("m1", "req", MutationState.CANDIDATE, 1,
                Instant.now(), root.toString(), null, null);
        MutationVerification.EvidenceReport report = MutationVerification.verify(m);
        assertFalse(report.verified());
        assertTrue(report.missing().contains("JAR_HASH_RECORDED"));
        assertTrue(report.missing().contains("MCP_CALL_SUCCESS"));
        assertTrue(report.missing().contains("WORLD_EFFECT_VERIFIED"));
    }

    private static void write(Path root, String rel, String content) throws Exception {
        write(root, rel, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path root, String rel, byte[] bytes) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }
}
