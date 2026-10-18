package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxToolPort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「当前<b>真的</b>注册了哪些积木」渲染成一段纯文本，供 AI 照抄。
 *
 * <p><b>为什么必须有它</b>（2026-10-06，用户实机验收）：
 * 学习者 AI 写 {@code ac_script_draft} 时<b>看不到任何积木清单</b> ——
 * 它唯一的参考是提示词里那个手写示例，而那个示例里写着<b>不存在的</b>
 * {@code block:"move"}（真名是 {@code goto}）。后果实测到两次：
 * <ol>
 *   <li>照抄示例 ⇒ 静态校验拒收；</li>
 *   <li>连拒之后它学会「交最小可解析版本」⇒ 交出的脚本
 *       {@code steps} 里只有一个 {@code get_self_status}，<b>零动作</b>，
 *       却真的被执行、真的报 {@code RUN_FINISHED SUCCESS} ——
 *       名字叫「低血量逃跑再喝奶」，实际只读了一次状态。</li>
 * </ol>
 *
 * <p><b>为什么不手写第二份清单</b>：手写必漂（上面那个 {@code move} 就是手写的产物）。
 * 这里<b>从注册表现场枚举</b>参数与输出，所以「AI 看到的」与「执行器认的」
 * 结构上不可能不一致。
 *
 * <p>纯 JVM、零 MC import（{@code numen-plugin.gradle:37-44} 封死跨插件 import），
 * 所以能离线单测。
 */
public final class AcxBlockCatalog {

    private AcxBlockCatalog() {
    }

    /**
     * 渲染整份目录。
     *
     * @param ports   真的注册进引擎的端口（来自宿主工具桥接）
     * @param aliases {@code 别名 → 真名}；只有真名注册成功时才列出该别名
     */
    public static String render(List<AcxToolPort> ports, Map<String, String> aliases) {
        List<AcxToolPort> ps = ports == null ? List.of() : ports;
        StringBuilder sb = new StringBuilder();
        sb.append("# ACX 可用积木（acx 插件按当前注册表自动生成，勿手改）\n");
        sb.append("# 规则：step 的 block 只能从下面的名字里挑；params 只能用该积木列出的参数名。\n");
        sb.append("#       名字不在表里 = 不存在，不要猜、不要自创（会被静态校验拒收）。\n");
        sb.append('\n');

        Set<String> real = new LinkedHashSet<>();
        for (AcxToolPort p : ps) {
            real.add(p.name());
        }

        for (AcxToolPort p : ps) {
            sb.append(p.name()).append(" — ").append(oneLine(p.description())).append('\n');
            AcxPortSchema s = p.schema();
            sb.append("    参数：");
            if (s == null || s.params().isEmpty()) {
                sb.append("（无）");
            } else {
                boolean first = true;
                for (Map.Entry<String, AcxPortSchema.Param> e : s.params().entrySet()) {
                    if (!first) {
                        sb.append("；");
                    }
                    first = false;
                    sb.append(e.getKey()).append(':').append(typeName(e.getValue().type()));
                    sb.append(e.getValue().required() ? " 必填" : " 可选");
                    if (e.getValue().nullableAllowed()) {
                        sb.append("(可为 null)");
                    }
                    if (!e.getValue().enumValues().isEmpty()) {
                        sb.append(" 枚举=").append(e.getValue().enumValues());
                    }
                    String d = e.getValue().description();
                    if (d != null && !d.isBlank()) {
                        sb.append(" —— ").append(oneLine(d));
                    }
                }
            }
            sb.append('\n');
            if (s != null && !s.outputFields().isEmpty()) {
                sb.append("    输出：").append(String.join(", ", s.outputFields())).append('\n');
            }
            sb.append('\n');
        }

        List<String> aliasLines = new ArrayList<>();
        if (aliases != null) {
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                if (real.contains(e.getValue())) {
                    aliasLines.add("    " + e.getKey() + " → " + e.getValue());
                }
            }
        }
        if (!aliasLines.isEmpty()) {
            sb.append("## 别名（写法不同，行为与它指向的真名完全一致，可以直接当 block 用）\n");
            sb.append(String.join("\n", aliasLines)).append('\n');
        }
        return sb.toString();
    }

    private static String typeName(AcxPortSchema.Type t) {
        if (t == null) {
            return "?";
        }
        return switch (t) {
            case STRING -> "字符串";
            case INTEGER -> "整数";
            case NUMBER -> "数字";
            case BOOLEAN -> "布尔";
            case STRING_ARRAY -> "字符串数组";
            case INT_ARRAY -> "整数数组";
            case OBJECT -> "对象";
            case OBJECT_ARRAY -> "对象数组";
            case ANY -> "任意";
        };
    }

    /** 多行描述压成一行 —— 目录是给模型读的，换行会把「参数/输出」的层级冲散。 */
    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }
}
