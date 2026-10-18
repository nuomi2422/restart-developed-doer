package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「执行结果 → 学习者」的<b>只读</b>通道（{@code 38} v3 §2.4 / v3.2 B22）。
 *
 * <p><b>数据源：只读 {@code config/numen/monitor/} 下的<b>已知名</b>，
 * 由调用方以 {@link #pull} 的 {@code jsonl} 参数传入</b>，
 * 不新建文件、不新增写入点、不改 {@code plugins/rdd}（理由见 v3.2 §1）。
 *
 * <p><b>2026-10-03（E1 批次）扩到第二个已知名</b>：{@code monitor/instrumentation.jsonl}
 * （{@code LearnerIntakeTool} 用来做入队判定）。原来这里写的是「只读 rdd.jsonl 一个已知名」，
 * 现在是<b>两个</b>——如实改掉，不让注释比代码更乐观。两个名字都是既有文件，
 * <b>仍然没有新增任何写入点</b>。
 *
 * <p><b>只读是编译期保证</b>：本类<b>只以 {@code Read} 方式</b>打开文件，
 * 整个类<b>没有任何</b> {@code write} / {@code append} / {@code delete} 调用。
 * 验收 V8 就是 grep 本文件里有没有写方法 —— 必须是 0。
 * （新增的 {@link #generationForConfigDir} 只推导路径，不碰文件内容。）
 *
 * <p><b>形态：拉取式游标</b>。工具被调用时才读<b>新增行</b>，不全量重读
 * （一个存档的 rdd.jsonl 能到几 MB，全量读会拖慢每次调用）。
 *
 * <p><b>generation 的替代方案（如实标注，不是真代际）</b>：
 * 只读通道<b>拿不到宿主</b>，所以没法照抄 {@code ServerLifecycleHooks.getCurrentServer()}。
 * 这里用「<b>存档名 + session.lock 的最后修改时间</b>」：
 * 换档 → 存档名变 → generation 变 → <b>旧游标自动失效</b>，
 * 不会把上一个世界的事件当成这一个世界的。
 * ⚠️ 它的粒度是<b>存档级</b>，比宿主代际粗；同档内换世界不会被发现。这是已知不足，不假装没有。
 */
public final class FeedbackChannel {

    private FeedbackChannel() {
    }

    private static final Gson GSON = new Gson();

    /** 单次拉取的行数上限。防止一次调用吐爆返回值（flow-gate/loop-gate 也会看返回体积）。 */
    public static final int MAX_LINES_PER_PULL = 200;

    /**
     * 单次拉取的**体积**上限（近似字符数）。
     *
     * <p><b>为什么加这个（2026-10-01 实机踩到）</b>：首次拉取时 {@code observation} 直接放事件
     * {@code data} 原样，而 {@code rdd.jsonl} 的 data 里嵌着<b>整条 taskChain</b>（全部 primary/subtask/condition）
     * 与<b>全量资产快照</b>（每个箱子/熔炉的完整槽位摘要）。实测单次返回 <b>83496 字符</b>，
     * <b>直接撞 MCP 的 16384 字符包上限</b>，整条回包被丢弃。
     *
     * <p>→ 所以：不是「原样 dump」，而是<b>压平 + 限量 + 显式标截断</b>。
     * 截断必须<b>说出来</b>（{@code observation_truncated:true}）——
     * 默默给一半比给全更危险（B21 的同一类：让下游以为那就是全部）。
     */
    public static final int MAX_OBSERVATION_CHARS = 6000;

    /** 嵌套深度上限：超过就不带（而不是整棵子树塞进来）。 */
    private static final int MAX_DEPTH = 2;

    /** 单个字符串值的长度上限。 */
    private static final int MAX_VALUE_CHARS = 300;

    /**
     * 算当前的存档级代际。
     *
     * @param saveDir     当前存档目录（其父目录名即存档名）
     * @param sessionLock 该存档的 session.lock（用来识别「这一局已经换过」）
     * @return 形如 {@code live@1759276800000}；<b>拿不到就返回 {@code "unknown"}</b>（不编造）
     */
    public static String generationOf(Path saveDir, Path sessionLock) {
        String saveName = saveDir == null || saveDir.getParent() == null
                ? "unknown"
                : saveDir.getFileName().toString();
        long stamp;
        try {
            stamp = sessionLock != null && Files.exists(sessionLock)
                    ? Files.getLastModifiedTime(sessionLock).toMillis()
                    : -1L;
        } catch (IOException e) {
            stamp = -1L;
        }
        return saveName + "@" + stamp;
    }

    /**
     * 从插件拿到的 {@code configDir} 直接算出存档级代际。
     *
     * <p><b>为什么单独抽出来（2026-10-03，E1 批次）</b>：这段路径推导原来写在
     * {@code LearnerFeedbackTool} 的调用处，而它的注释里记着一条实测教训 ——
     * 2026-10-01「改了一次只修一半」：{@code configDir = <实例>/config/numen}，
     * 要上两级才到 {@code <实例>/saves}，当时只改了工具里的推导、没改别处，
     * 结果存档名对了但 {@code session.lock} 读不到 → generation 变成 {@code "live@-1"}。
     * <b>⇒ 同一段路径推导只许写一次</b>。现在只有这里写，两个工具都调它。
     *
     * @param configDir {@code NumenApi.configDir()}，即 {@code <实例>/config/numen}；可为 null
     * @return 形如 {@code live@1759276800000}；<b>任何一环推不出来就返回 {@code "unknown"}</b>，不编造
     */
    public static String generationForConfigDir(Path configDir) {
        if (configDir == null) {
            return "unknown";
        }
        Path configParent = configDir.getParent();
        Path instanceDir = configParent == null ? null : configParent.getParent();
        Path savesDir = instanceDir == null ? null : instanceDir.resolve("saves");
        if (savesDir == null || !Files.exists(savesDir)) {
            return "unknown";
        }
        String saveName = newestSaveName(savesDir);
        Path saveDir = savesDir.resolve(saveName);
        return generationOf(saveDir, saveDir.resolve("session.lock"));
    }

    /** 在给定的 saves 目录里挑 {@code session.lock} 最后修改时间最新的那个存档名。 */
    private static String newestSaveName(Path savesDir) {
        try (var s = Files.list(savesDir)) {
            Path newest = null;
            long newestT = -1L;
            for (Path p : (Iterable<Path>) s.filter(Files::isDirectory)::iterator) {
                Path lock = p.resolve("session.lock");
                if (!Files.exists(lock)) {
                    continue;
                }
                long t = Files.getLastModifiedTime(lock).toMillis();
                if (t > newestT) {
                    newestT = t;
                    newest = p;
                }
            }
            return newest == null ? "unknown" : newest.getFileName().toString();
        } catch (IOException | RuntimeException e) {
            return "unknown";
        }
    }

    /**
     * 拉取游标之后的新增事件。
     *
     * <p><b>纯读</b>。坏行<b>跳过并计数</b>，不抛也不静默丢（对齐 {@code experience-guard.js}
     * 的写入闸精神：出问题要看得见）。
     *
     * @param jsonl        {@code rdd.jsonl} 路径（不存在时返回空结果，不是异常）
     * @param generation   当前代际，会逐条盖在事件上（让下游不用自己判代际）
     * @param cursorByte   上次读到的字节偏移；首次传 0
     * @param kinds        只保留这些 kind；传 {@code null} 或空数组 = <b>全留</b>
     * @param readResult   出参容器，会被填 {@code {events, nextCursor, skipped, truncated}}
     * @return 结果对象（同时就是 readResult，见 {@link Pull}）
     */
    public static Pull pull(Path jsonl, String generation, long cursorByte,
                             List<String> kinds, Pull readResult) {
        Pull out = readResult != null ? readResult : new Pull();
        out.events.clear();
        out.skipped = 0;
        out.truncated = false;
        out.nextCursor = cursorByte;

        if (jsonl == null || !Files.exists(jsonl)) {
            out.readable = false;
            return out;
        }
        out.readable = true;

        long size;
        try {
            size = Files.size(jsonl);
        } catch (IOException e) {
            out.readable = false;
            return out;
        }
        // 文件被截断/重建（换档）→ 旧偏移失效，从头读，但代际已经变了，下游能分辨
        if (cursorByte > size) {
            cursorByte = 0L;
        }

        // ★ B9 实机抓到的真 bug（2026-10-03）：原来这段**只把游标用来算「读多少字节」，
        //   却从来没真的跳过** —— 字节永远从文件头读起。结果：任何非零游标都被静默忽略，
        //   每次都返回**文件里最老的那批事件**，AI 推进游标也永远看不到新事件。
        //   两条通道（learner_feedback / learner_intake）从上线起就是这个行为。
        //   为什么单测没抓到：唯一测「第二次拉只返回新增」的那条断言写成了恒真式
        //   （两个分支返回同一个字符串），见 FeedbackChannelB22Test 同名测试。
        long start = Math.max(0L, cursorByte);
        if (start > 0) {
            // 先探一次能不能真的跳到那个位置。跳不满 = 文件在读的过程中被换档/截断了，
            // 宁可退回从头读，也不能把「从错误位置读到的字节」当成新增事件报出去。
            long actually;
            try (var probe = Files.newInputStream(jsonl)) {
                actually = skipFully(probe, start);
            } catch (IOException e) {
                out.readable = false;
                return out;
            }
            if (actually != start) {
                start = 0L;
            }
        }

        byte[] bytes;
        try (var in = Files.newInputStream(jsonl)) {
            long remaining = size - start;
            if (remaining <= 0) {
                return out;
            }
            if (start > 0 && skipFully(in, start) != start) {
                out.readable = false;
                return out;
            }
            bytes = in.readNBytes((int) Math.min(remaining, 8L * 1024 * 1024));
        } catch (IOException e) {
            out.readable = false;
            return out;
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        long consumed = start;
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            // 只把**完整行**计入游标：末尾那半行留给下次，避免读半条 JSON
            boolean lastLineNoNewline = !text.endsWith("\n") && line.equals(text.substring(text.lastIndexOf('\n') + 1));
            consumed += line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (lastLineNoNewline) {
                break;
            }
            FeedbackEvent ev = parseLine(line, generation);
            if (ev == null) {
                out.skipped++;
                continue;
            }
            if (kinds != null && !kinds.isEmpty() && !ev.isKind(kinds.toArray(new String[0]))) {
                continue;
            }
            if (out.events.size() >= MAX_LINES_PER_PULL) {
                out.truncated = true;
                break;
            }
            out.events.add(ev);
        }
        out.nextCursor = Math.min(consumed, size);
        return out;
    }

    /**
     * 一直跳到 {@code target} 字节或跳不动为止，返回真正跳过的字节数。
     *
     * <p><b>为什么不能只调一次 {@code skip}</b>：{@link java.io.InputStream#skip(long)}
     * 允许只跳一部分（无缓冲的流一次最多跳到缓冲区边界），跳少了就得接着跳 ——
     * 静默跳少了就会读到「错误位置上的字节」，也就是 B9 修的那个 bug 的另一种形态。</p>
     *
     * <p>⚠️ 顺带记一个我自己踩的坑：{@code skipNBytes(long)} 的返回值是
     * <b>void</b>，不是 long（返回 long 的是 {@code skip(long)}）。写
     * {@code long x = in.skipNBytes(n);} 编译不过。</p>
     */
    private static long skipFully(java.io.InputStream in, long target) throws IOException {
        long done = 0L;
        while (done < target) {
            long n = in.skip(target - done);
            if (n <= 0L) {
                // skip 返回 0 不代表到头，但也不能再推进了 —— 交给调用方当「跳不满」处理。
                break;
            }
            done += n;
        }
        return done;
    }

    /** 截断标记键。出现即代表 observation **不完整**，下游必须知道。 */
    public static final String OBS_TRUNCATED = "observation_truncated";

    /**
     * 把 {@code data} 压平成「点路径 → 标量」的一层。
     *
     * <p><b>为什么压平而不是嵌套</b>：学习者要判断的是「发生了什么」，
     * 例如 {@code taskChain.currentSubtaskId=...}、{@code assets.0.value.block=...}。
     * 把整棵子树原样塞进返回值，会让<b>一条事件占掉整个 MCP 包</b>（实测 83496 字符），
     * 而真正被读到的往往只是最上面两三层。
     *
     * <p><b>截断必须说出来</b>：超限时置 {@link #OBS_TRUNCATED}=true。
     * 默默给一半，比明确说「这里还有更多」危险得多。
     */
    static Map<String, Object> flatten(JsonObject data, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (data == null) {
            return out;
        }
        int budget = MAX_OBSERVATION_CHARS;
        boolean truncated = false;
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            if (budget <= 0) {
                truncated = true;
                break;
            }
            JsonElement v = e.getValue();
            String key = e.getKey();
            if (v == null || v.isJsonNull()) {
                continue;
            }
            if (v.isJsonPrimitive()) {
                String s = v.getAsString();
                if (s.length() > MAX_VALUE_CHARS) {
                    s = s.substring(0, MAX_VALUE_CHARS) + "…[cut " + (v.getAsString().length() - MAX_VALUE_CHARS) + " chars]";
                    truncated = true;
                }
                out.put(key, s);
                budget -= key.length() + s.length();
            } else if (v.isJsonArray()) {
                // 数组只取「长度 + 前几个**标量**元素」，不整棵展开
                int n = v.getAsJsonArray().size();
                out.put(key + ".count", String.valueOf(n));
                budget -= key.length() + 12;
                if (depth < MAX_DEPTH) {
                    int taken = 0;
                    boolean droppedObjects = false;
                    for (JsonElement item : v.getAsJsonArray()) {
                        if (taken >= 5) {
                            truncated = true;
                            break;
                        }
                        if (item != null && item.isJsonPrimitive()) {
                            String s = item.getAsString();
                            if (s.length() > MAX_VALUE_CHARS) {
                                s = s.substring(0, MAX_VALUE_CHARS) + "…[cut]";
                                truncated = true;
                            }
                            out.put(key + "[" + taken + "]", s);
                            budget -= key.length() + s.length();
                            taken++;
                        } else if (item != null && item.isJsonObject()) {
                            // 对象元素整棵丢掉 → **必须标截断**（B21：默默丢内容 = 让下游以为那就是全部）
                            droppedObjects = true;
                        }
                    }
                    if (droppedObjects) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            } else if (v.isJsonObject()) {
                if (depth < MAX_DEPTH) {
                    Map<String, Object> child = flatten(v.getAsJsonObject(), depth + 1);
                    for (Map.Entry<String, Object> ce : child.entrySet()) {
                        String ck = key + "." + ce.getKey();
                        out.put(ck, ce.getValue());
                        budget -= ck.length() + String.valueOf(ce.getValue()).length();
                    }
                    if (child.containsKey(OBS_TRUNCATED)) {
                        truncated = true;
                    }
                } else {
                    // 深度超限：只留「这个键存在，有 N 个子键」，不展开
                    out.put(key + ".<depth>", String.valueOf(v.getAsJsonObject().size()));
                    budget -= key.length() + 8;
                    truncated = true;
                }
            }
        }
        if (truncated) {
            out.put(OBS_TRUNCATED, "true");
        }
        return out;
    }

    /** 单行解析。坏行返回 {@code null}（由调用方计入 skipped）。 */
    static FeedbackEvent parseLine(String line, String generation) {
        try {
            JsonElement root = JsonParser.parseString(line);
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonObject o = root.getAsJsonObject();
            String type = str(o, "type");
            if (type == null || type.isBlank()) {
                return null;
            }
            JsonObject data = o.has("data") && o.get("data").isJsonObject()
                    ? o.getAsJsonObject("data") : new JsonObject();

            // 批次身份 + 数量（RL-15）：只放源事件里真实存在的键，不补默认值（B21）
            Map<String, Object> subject = new LinkedHashMap<>();
            for (String k : new String[]{"companion", "primary", "subtask", "stage", "reason", "count", "items", "gen"}) {
                String v = str(data, k);
                if (v != null) {
                    subject.put(k, v);
                }
            }
            Map<String, Object> flat = flatten(data, 0);
            Map<String, Object> observation = new LinkedHashMap<>(flat);
            if (flat.containsKey(OBS_TRUNCATED)) {
                observation.remove(OBS_TRUNCATED);
                observation.put(OBS_TRUNCATED, true);
            }

            // eventId 优先取源里的，其次用「类型+时间」派生（保证同一条不会重复）
            String id = str(o, "event_id");
            if (id == null || id.isBlank()) {
                String ts = firstNonBlank(str(o, "timestamp"), str(o, "game_time"));
                id = type + "@" + (ts == null ? "?" : ts);
            }
            String ts = firstNonBlank(str(o, "timestamp"), str(o, "game_time"));
            return new FeedbackEvent(id, generation, ts, type, kindOf(type), subject, observation, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 把源事件类型归到 kind 家族。认不出来就 {@code "other"}，**不编一个看起来对的家族**。 */
    static String kindOf(String type) {
        String t = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("subtask") || t.startsWith("primary") || t.startsWith("taskchain")
                || t.startsWith("planning") || t.startsWith("dependency")) {
            return t.startsWith("planning") || t.startsWith("dependency") ? "planning" : "task";
        }
        if (t.contains("death") || t.contains("respawn") || t.contains("recovery")) {
            return "death";
        }
        if (t.contains("combat") || t.contains("attack") || t.contains("hurt") || t.contains("damage")) {
            return "combat";
        }
        if (t.contains("asset") || t.contains("inventory") || t.contains("item") || t.contains("recover")) {
            return "asset";
        }
        return "other";
    }

    private static String str(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) {
            return null;
        }
        JsonElement e = o.get(k);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }

    /**
     * 拉取结果。出参容器，顺带承担「诊断信息」的职责。
     *
     * <p>⚠️ {@code readable=false} 是<b>诚实的失败</b>（文件不在 / 读不了），
     * 与「读到 0 条」是不同的事 —— 下游不许把两者混为一谈。
     */
    public static final class Pull {
        public final List<FeedbackEvent> events = new ArrayList<>();
        public long nextCursor;
        public int skipped;
        public boolean truncated;
        public boolean readable;
    }
}