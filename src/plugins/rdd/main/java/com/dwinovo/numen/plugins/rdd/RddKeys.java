package com.dwinovo.numen.plugins.rdd;

import java.util.regex.Pattern;

/**
 * LLM 产出资产键的可执行性形状校验。
 *
 * <p>真机背包键来自 {@code BuiltInRegistries.ITEM.getKey()}——一律小写命名空间形式
 * （minecraft:oak_log / twilightforest:…）。裸键（stone_pickaxe）、占位（goal）、
 * 大写（minecraft:Diamond）永远匹配不上背包 → 是"合法 JSON 但结构不可执行"的死条件。
 * 这类键必须在进入任务链前被丢弃（宁缺毋滥），而不是让二级/一级门永久不满足造成假卡死。
 */
final class RddKeys {
    /** 资源路径形状：小写 namespace + ':' + 小写 path（含 mod 命名空间）。 */
    private static final Pattern ASSET_KEY =
            Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    private RddKeys() {}

    /** 资产键形状是否可执行：非空 + 匹配 ns:path。 */
    static boolean usable(String key) {
        return key != null && ASSET_KEY.matcher(key).matches();
    }
}
