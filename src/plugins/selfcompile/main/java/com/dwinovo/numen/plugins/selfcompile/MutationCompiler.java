package com.dwinovo.numen.plugins.selfcompile;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Compiles only sources already accepted by the deterministic source gate. */
public final class MutationCompiler {
    private static final long MAX_SOURCE_BYTES = 200_000L;

    public CompileResult compile(MutationManifest manifest) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        Path workspace = Path.of(manifest.workspace()).toAbsolutePath().normalize();
        Path sourceDir = workspace.resolve("source").normalize();
        Path classesDir = workspace.resolve("classes").normalize();
        if (!Files.isDirectory(sourceDir) || !Files.isDirectory(classesDir)) {
            return new CompileResult(false, List.of("source/classes directory missing"), -1);
        }
        List<Path> sources = new ArrayList<>();
        try (var stream = Files.list(sourceDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted().forEach(sources::add);
        }
        if (sources.isEmpty()) return new CompileResult(false, List.of("no Java source files"), -1);
        for (Path source : sources) {
            if (Files.size(source) > MAX_SOURCE_BYTES) {
                return new CompileResult(false, List.of("source exceeds 200000 bytes: " + source.getFileName()), -1);
            }
            MutationStaticChecker.CheckResult gate = MutationStaticChecker.check(
                    Files.readString(source, StandardCharsets.UTF_8));
            if (!gate.accepted()) return new CompileResult(false, gate.violations(), -1);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return new CompileResult(false, List.of("JDK compiler unavailable"), -1);
        List<String> args = new ArrayList<>();
        args.add("-encoding");
        args.add("UTF-8");
        args.add("-proc:none");
        args.add("-d");
        args.add(classesDir.toString());
        String classpath = System.getProperty("java.class.path", "");
        if (!classpath.isBlank()) {
            args.add("-classpath");
            args.add(classpath);
        }
        sources.forEach(path -> args.add(path.toString()));
        Path report = workspace.resolve("reports").resolve("compile.log");
        Files.createDirectories(report.getParent());
        // 用 ByteArrayOutputStream + UTF-8 解码，而不是 write(int b) 逐字节转 char：
        // 后者会把多字节 UTF-8 中文拆成乱码字符，MutationErrorParser 匹配不到 `错误:`。
        var byteOut = new java.io.ByteArrayOutputStream();
        int exit = compiler.run(null, byteOut, byteOut, args.toArray(String[]::new));
        String output = byteOut.toString(StandardCharsets.UTF_8);
        Files.writeString(report, output, StandardCharsets.UTF_8);
        return new CompileResult(exit == 0, output.isBlank()
                ? List.of() : List.of(output), exit);
    }

    public record CompileResult(boolean success, List<String> diagnostics, int exitCode) {}
}
