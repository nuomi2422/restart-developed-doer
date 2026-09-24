package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置高质量经验的<b>只读层</b>：出厂知识，与同伴自己学到的经验分开放。
 *
 * <p>文件 {@code config/numen/knowledge/builtin-experience.jsonl}，格式与同伴经验库一致
 * （复用 {@link ExperienceEntry#fromJson}，不另造一套持久化格式）。区别在于：
 * <ul>
 *   <li><b>只读</b>：本层永不被写回、不参与 learn/recordEvidence，同伴的学习只落在它自己的
 *       {@code experience-<uuid>.jsonl}。</li>
 *   <li><b>出处可辨</b>：注入时 origin 固定为 {@code builtin}，监测台一眼能区分
 *       “出厂知识”与“这只同伴亲历”。</li>
 *   <li><b>参与同一召回池</b>：与同伴经验混合排序（成熟度优先），所以高质量的出厂条目
 *       在同伴还没学到东西时就能顶上。</li>
 * </ul>
 *
 * <p>文件缺失/损坏一律当作“没有内置知识”，返回空列表，绝不让规划失败。
 */
public final class BuiltinExperience {

    /** 内置经验文件相对 {@code config/numen/} 的路径。 */
    public static final String RELATIVE_PATH = "knowledge/builtin-experience.jsonl";

    /** 注入时使用的来源标识；审计上必须有别于 {@code experience-<uuid>.jsonl}。 */
    public static final String ORIGIN = "builtin";

    /** 缓存：文件可能很大，但每轮规划都要读，按 mtime+size 失效即可。 */
    private static volatile List<PlanningKnowledge.Item> cache = List.of();
    private static volatile long cachedStamp = -1L;

    private static final Gson GSON = new Gson();

    private BuiltinExperience() {}

    /** 取内置经验（可能为空）。 */
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
            List<PlanningKnowledge.Item> parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
            cache = parsed;
            cachedStamp = stamp;
            return parsed;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /**
     * 解析 JSONL 为候选知识（纯函数，可单测）。
     * 坏行跳过、缺字段按默认值，整体永不抛异常。
     */
    public static List<PlanningKnowledge.Item> parse(String jsonl) {
        List<PlanningKnowledge.Item> out = new ArrayList<>();
        if (jsonl == null || jsonl.isBlank()) {
            return out;
        }
        int lineNo = 0;
        for (String line : jsonl.split("\r?\n")) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            try {
                JsonObject o = GSON.fromJson(trimmed, JsonObject.class);
                if (o == null) {
                    continue;
                }
                ExperienceEntry entry = ExperienceEntry.fromJson(o);
                PlanningKnowledge.Item item = toItem(entry, lineNo);
                if (item != null) {
                    out.add(item);
                }
            } catch (RuntimeException ignored) {
                // 坏行跳过：一行坏掉不该让整包内置知识失效
            }
        }
        return out;
    }

    /** 条目 → 候选知识；三个正文字段全空则丢弃（空壳知识没有注入价值）。 */
    private static PlanningKnowledge.Item toItem(ExperienceEntry entry, int lineNo) {
        if (entry == null) {
            return null;
        }
        String response = entry.recommendedResponse().isBlank() ? entry.description() : entry.recommendedResponse();
        if (entry.title().isBlank() && response.isBlank() && entry.rootCause().isBlank()) {
            return null;
        }
        String id = entry.id().isBlank() ? "builtin:line" + lineNo : entry.id();
        ExperienceMaturity maturity = entry.maturity();
        List<String> tags = new ArrayList<>(entry.tags());
        tags.addAll(entry.triggerStrings());
        tags.addAll(entry.toolNames());
        return new PlanningKnowledge.Item(
                id,
                PlanningKnowledge.Kind.EXPERIENCE,
                entry.title(),
                maturity == null ? "OBSERVED" : maturity.name(),
                entry.rootCause(),
                response,
                ORIGIN,
                // 内置条目没有真实召回得分，用 priority 当基础分：写得越重要越靠前
                entry.priority(),
                tags);
    }

    private static Path configNumenDir() {
        return FMLPaths.GAMEDIR.get().resolve("config").resolve("numen");
    }
}
