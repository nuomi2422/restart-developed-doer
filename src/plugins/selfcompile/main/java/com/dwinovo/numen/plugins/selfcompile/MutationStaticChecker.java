package com.dwinovo.numen.plugins.selfcompile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Cheap, deterministic source gate that runs before any compiler or class loader. */
public final class MutationStaticChecker {
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+com\\.dwinovo\\.numen\\.plugins\\.selfcompile\\.generated\\s*;");
    private static final List<String> FORBIDDEN = List.of(
            "Runtime.getRuntime().exec",
            "new ProcessBuilder",
            "System.exit",
            "Files.delete",
            "Files.deleteIfExists",
            "java.lang.reflect",
            "java.net.Socket",
            "java.net.ServerSocket"
    );

    private MutationStaticChecker() {}

    public static CheckResult check(String source) {
        List<String> violations = new ArrayList<>();
        if (source == null || source.isBlank()) {
            violations.add("source is blank");
            return new CheckResult(false, violations);
        }
        if (!PACKAGE.matcher(source).find()) violations.add("generated package is not allowed");
        for (String token : FORBIDDEN) {
            if (source.contains(token)) violations.add("forbidden token: " + token);
        }
        if (source.length() > 200_000) violations.add("source exceeds 200000 characters");
        return new CheckResult(violations.isEmpty(), List.copyOf(violations));
    }

    public record CheckResult(boolean accepted, List<String> violations) {}
}
