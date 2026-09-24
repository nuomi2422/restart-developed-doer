package com.dwinovo.numen.plugins.experience;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 可移植攻略（Markdown）→ {@link PlanningKnowledge.Item} 列表。
 *
 * <p>{@link #parse(String, String)} 是纯函数（可独立单测）；{@link #load()} 负责定位文件并兜底，
 * 任何缺失/损坏都返回空列表 + 缺口标记，绝不让规划因为“攻略文件坏了”而失败。
 *
 * <p>攻略文件预期放在 {@code config/numen/knowledge/mc-guide.md}（不随插件 jar 走，
 * 便于随时更新内容而不用重编译）。文件不存在时视为没有攻略知识，而不是错误。
 */
public final class GuideKnowledge {

    /** 攻略文件的相对路径（相对 {@code config/numen/}）。 */
    public static final String RELATIVE_PATH = "knowledge/mc-guide.md";

    private GuideKnowledge() {}

    /**
     * 解析 Markdown：每个 {@code ## } 小节变成一条知识。
     *
     * <p>首个二级标题之前的前言（用途说明、定位声明）不算知识，跳过——那些是给人和
     * 维护者看的元信息，塞进规划请求只会挤占预算。
     *
     * @param markdown 文档正文（可为 null）
     * @param origin   出处标识，写进条目供监测台核对来源
     */
    public static List<PlanningKnowledge.Item> parse(String markdown, String origin) {
        List<PlanningKnowledge.Item> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        String currentTitle = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\r?\n", -1)) {
            if (line.startsWith("## ")) {
                flush(out, currentTitle, body, origin);
                currentTitle = line.substring(3).trim();
                body.setLength(0);
                continue;
            }
            if (currentTitle != null) {
                body.append(line).append('\n');
            }
        }
        flush(out, currentTitle, body, origin);
        return out;
    }

    private static void flush(List<PlanningKnowledge.Item> out, String title, StringBuilder body, String origin) {
        if (title == null || title.isBlank()) {
            return;
        }
        String text = body.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        out.add(new PlanningKnowledge.Item(
                "guide:" + title,
                PlanningKnowledge.Kind.GUIDE,
                title,
                "STATIC",
                "",
                text,
                origin,
                0.0,
                List.of(title)));
    }

    /** 缓存：攻略每轮规划都要读，按 mtime+size 失效，避免在规划线程上反复读盘。 */
    private static volatile List<PlanningKnowledge.Item> cache = List.of();
    private static volatile long cachedStamp = -1L;

    /**
     * 从 {@code config/numen/knowledge/mc-guide.md} 读攻略。读不到返回空列表（不是异常）。
     */
    public static List<PlanningKnowledge.Item> load() {
        try {
            Path file = configNumenDir().resolve(RELATIVE_PATH);
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            long stamp = Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file);
            if (stamp == cachedStamp) {
                return cache;
            }
            List<PlanningKnowledge.Item> parsed = parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            cache = parsed;
            cachedStamp = stamp;
            return parsed;
        } catch (Throwable ignored) {
            // 缺失/权限/编码/宿主环境缺类都按“没有攻略知识”处理，规划照常进行
            return List.of();
        }
    }

    private static Path configNumenDir() {
        return FMLPaths.GAMEDIR.get().resolve("config").resolve("numen");
    }
}
