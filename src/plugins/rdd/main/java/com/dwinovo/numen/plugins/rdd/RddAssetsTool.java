package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Read-only inspection of reusable bases, locations, machines and entity sightings. */
final class RddAssetsTool implements NumenTool {

    private static final Gson GSON = new Gson();
    /** 一次最多回多少条：资产库随探索持续增长，全量回会撑爆网络包（见 onServerCall）。 */
    private static final int MAX_SHOWN = 40;
    /** 结果正文的字符预算，远低于 MC 网络包的 16384 上限，给信封和后续消息留余量。 */
    private static final int MAX_CHARS = 8000;

    @Override public String name() { return "rdd_assets"; }
    @Override public String description() {
        return "Read RDD reusable world assets: bases, structures/locations, machines and notable entity sightings. "
                + "LAZY sightings are historical leads and must be rechecked before use. "
                + "Returns the most recently observed entries; the newest ones matter most.";
    }
    @Override public Map<String, Object> parameterSchema() { return Schema.none(); }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        // 这里曾经回全量：105 条资产序列化后 52,702 字符，超过 MC 网络包 16,384 字符上限，
        // ByteBufCodecs 编码时抛 EncoderException，主人掉线、单人服务端因登出而停止，
        // 整场自主跑就这么死掉（日志里只剩一串 netty 栈）。
        // 现在按「条数 + 字符」双上限裁剪；worldAssets 已按观测时间倒序，保留最新的那批。
        List<AssetRegistry.AssetEntry> all = RddAssetContext.worldAssets(RddPlugin.assets(companion.getUUID()));
        List<AssetRegistry.AssetEntry> shown = new ArrayList<>();
        int chars = 0;
        for (AssetRegistry.AssetEntry entry : all) {
            if (shown.size() >= MAX_SHOWN) break;
            int size = GSON.toJson(entry).length() + 1;
            if (chars + size > MAX_CHARS) break;
            shown.add(entry);
            chars += size;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", all.size());
        data.put("shown", shown.size());
        if (shown.size() < all.size()) {
            data.put("truncated", "newest " + shown.size() + " of " + all.size()
                    + " shown, to stay inside the packet size limit");
        }
        data.put("assets", shown);
        reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD reusable world assets", data).toJson());
    }
}
