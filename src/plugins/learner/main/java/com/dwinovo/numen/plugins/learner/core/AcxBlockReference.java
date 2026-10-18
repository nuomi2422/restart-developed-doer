package com.dwinovo.numen.plugins.learner.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 只读 {@code <config>/numen/acx/blocks.txt} —— acx 插件写出的「当前真的有哪些积木」清单。
 *
 * <p><b>为什么 learner 需要它</b>（2026-10-06，用户实机验收）：
 * 学习者写 {@code ac_script_draft} 时此前<b>看不到任何积木清单</b>，只能照抄提示词里
 * 那个手写示例 —— 而那个示例写着<b>不存在的</b> {@code block:"move"}（真名 {@code goto}）。
 * 实测后果：连拒三次后 AI 学会交「最小可解析空壳」（只读状态、零动作），
 * 却真的被执行、真的报 {@code RUN_FINISHED SUCCESS}。
 *
 * <p><b>读不到时的契约</b>：<b>绝不返回空串冒充「没有积木」</b> ——
 * 那会让 AI 以为清单为空、进而自由发挥。读不到要说「读不到」，并明确要求
 * <b>这次不要写 ac_script_draft</b>。这是本工程反复踩的同一类坑
 *（「0」有三种含义，第三种是上游断了读不到）。
 */
public final class AcxBlockReference {

    /** 与 {@code AcxPlugin.writeBlockCatalog} 的落点必须一致（跨插件契约，改一处要改两处）。 */
    public static final String RELATIVE_PATH = "acx/blocks.txt";

    private AcxBlockReference() {
    }

    /**
     * 给复盘提示词用的积木清单块。
     *
     * @param configDir 配置根目录（{@code LearnerPlugin.configDir()}）；{@code null} = 还没就绪
     */
    public static String promptBlock(Path configDir) {
        if (configDir == null) {
            return missing("配置目录尚未就绪");
        }
        Path f = configDir.resolve("acx").resolve("blocks.txt");
        if (!Files.isRegularFile(f)) {
            return missing("文件不存在（" + f + "）—— 多发生在还没调用过任何 acx_* 工具之前");
        }
        String text;
        try {
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return missing("读取失败：" + e);
        }
        if (text.isBlank()) {
            return missing("文件是空的（" + f + "）");
        }
        return "【可用积木】以下是 ACX 当前真的注册了的积木（acx 插件自动生成，不是手写清单）：\n"
                + text + "\n";
    }

    private static String missing(String why) {
        return "【可用积木】读不到 —— " + why + "。\n"
                + "⇒ 这一次**不要**写 ac_script_draft（没有清单就一定会猜错 block 名，"
                + "交出去只会被静态校验拒收，或者变成一个什么都不做的空壳）。\n"
                + "宁可给 NO_ACTION / NEEDS_HUMAN 并写清差哪块能力。\n";
    }
}
