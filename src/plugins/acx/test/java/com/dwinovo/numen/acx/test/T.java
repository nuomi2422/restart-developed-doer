package com.dwinovo.numen.acx.test;

import java.util.ArrayList;
import java.util.List;

/** 极简断言 + 计数。隔离工作区没有 gradle，所以不用 JUnit。 */
public final class T {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int total;
    private static String group = "";

    private T() { }

    public static void group(String name) {
        group = name;
        System.out.println();
        System.out.println("── " + name);
    }

    public static void test(String name, Runnable body) {
        total++;
        try {
            body.run();
            System.out.println("   PASS  " + name);
        } catch (Throwable e) {
            String msg = group + " / " + name + "  →  " + e;
            FAILURES.add(msg);
            System.out.println("   FAIL  " + name + "  →  " + e);
        }
    }

    public static void eq(Object expected, Object actual, String what) {
        if (!eqv(expected, actual)) {
            throw new AssertionError(what + ": 期望 <" + expected + "> 实际 <" + actual + ">");
        }
    }

    public static void isTrue(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError(what + ": 期望 true");
        }
    }

    public static void contains(String haystack, String needle, String what) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(what + ": <" + haystack + "> 里找不到 <" + needle + ">");
        }
    }

    public static void notContains(String haystack, String needle, String what) {
        if (haystack != null && haystack.contains(needle)) {
            throw new AssertionError(what + ": <" + haystack + "> 里不该出现 <" + needle + ">");
        }
    }

    public static void isNull(Object o, String what) {
        if (o != null) {
            throw new AssertionError(what + ": 期望 null 实际 <" + o + ">");
        }
    }

    public static void notNull(Object o, String what) {
        if (o == null) {
            throw new AssertionError(what + ": 期望非 null");
        }
    }

    public static <E extends Throwable> E throwsA(Class<E> type, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError("期望抛 " + type.getSimpleName() + "，实际抛 " + t);
        }
        throw new AssertionError("期望抛 " + type.getSimpleName() + "，但没抛");
    }

    private static boolean eqv(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return a.equals(b);
    }

    public static int summary() {
        System.out.println();
        System.out.println("════════════════════════════════════");
        System.out.println("总计 " + total + " 项，通过 " + (total - FAILURES.size()) + "，失败 " + FAILURES.size());
        if (!FAILURES.isEmpty()) {
            System.out.println("失败明细:");
            for (String f : FAILURES) {
                System.out.println("  ✗ " + f);
            }
        }
        System.out.println("════════════════════════════════════");
        writeResultFile();
        return FAILURES.isEmpty() ? 0 : 1;
    }

    /** 给 verify-batch.ps1 用的机器可读结果（-Dacx.resultFile=...）。 */
    private static void writeResultFile() {
        String path = System.getProperty("acx.resultFile");
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"total\":").append(total)
          .append(",\"passed\":").append(total - FAILURES.size())
          .append(",\"failed\":").append(FAILURES.size())
          .append(",\"failures\":[");
        for (int i = 0; i < FAILURES.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(FAILURES.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        sb.append("]}");
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(path),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("   WARN  result file not written: " + e);
        }
    }
}
