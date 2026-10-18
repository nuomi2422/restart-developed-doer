package com.dwinovo.numen.acx.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.dwinovo.numen.acx.api.AcxDefinition;

/**
 * AC 定义的稳定指纹：canonical JSON + SHA-256。
 *
 * <p><b>为什么必须 canonical</b>：resume 校验要判断「断点前后是不是同一份 AC」。
 * 直接对 JSON 文本取哈希会因为 key 顺序、空白、字段书写顺序不同而误判为「定义变了」，
 * 于是合法 resume 被拒。canonical 化把 key 排序、集合排序、数字归一，
 * 只有语义真的变了哈希才变。</p>
 *
 * <p>与现有 {@code AcFingerprint} 的差别：现有版只 canonical 化顶层，本版递归处理嵌套
 * children（因为 steps 现在可嵌套）。</p>
 */
public final class AcxFingerprint {

    private AcxFingerprint() { }

    public static String of(AcxDefinition def) {
        return sha256(canonical(def.toMap()));
    }

    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            // key 排序：Map.of / HashMap 的迭代顺序不稳定，必须显式排序
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            List<Object> items = new ArrayList<>(c);
            // 集合（tags 之类）排序后再写，避免顺序抖动造成假变更
            if (allScalar(items)) {
                items.sort((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
            }
            sb.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(items.get(i), sb);
            }
            sb.append(']');
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else {
            writeString(String.valueOf(v), sb);
        }
    }

    private static boolean allScalar(List<Object> items) {
        for (Object o : items) {
            if (o instanceof Map || o instanceof Collection) {
                return false;
            }
        }
        return true;
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}