package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MutationCompilerTest {
    @Test
    void compilesAcceptedSourceAndWritesDiagnostics() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-compiler-");
        MutationWorkspace workspace = new MutationWorkspace(root);
        MutationManifest manifest = workspace.create("compile");
        new MutationSourceStore(workspace).write(manifest, "Tool.java",
                "package com.dwinovo.numen.plugins.selfcompile.generated;\npublic final class Tool {}\n");

        MutationCompiler.CompileResult result = new MutationCompiler().compile(manifest);
        assertTrue(result.success(), result.diagnostics().toString());
        assertEquals(0, result.exitCode());
        assertTrue(Files.exists(Path.of(manifest.workspace(), "classes", "com", "dwinovo", "numen", "plugins", "selfcompile", "generated", "Tool.class")));
        assertTrue(Files.exists(Path.of(manifest.workspace(), "reports", "compile.log")));
    }

    @Test
    void rejectsMissingSourcesAndBrokenJava() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-compiler-");
        MutationWorkspace workspace = new MutationWorkspace(root);
        MutationManifest empty = workspace.create("empty");
        assertFalse(new MutationCompiler().compile(empty).success());

        MutationManifest broken = workspace.create("broken");
        new MutationSourceStore(workspace).write(broken, "Broken.java",
                "package com.dwinovo.numen.plugins.selfcompile.generated;\npublic final class Broken {\n");
        // SourceStore accepts syntax-neutral text; compiler must report the real syntax failure.
        MutationCompiler.CompileResult result = new MutationCompiler().compile(broken);
        assertFalse(result.success());
        assertNotEquals(0, result.exitCode());
    }
}
