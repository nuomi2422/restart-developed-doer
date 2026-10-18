package com.dwinovo.numen.acx.test;

import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.core.AcxBlockCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积木清单渲染的契约测试（2026-10-06）。
 *
 * <p><b>为什么值得单测</b>：这份清单是 AI 写 AC 时<b>唯一的</b>名字来源。
 * 它一旦漏了某个积木、或者把别名列成不可用的样子，症状不是报错，而是
 * <b>AI 照着清单写、然后被静态校验拒收</b> —— 而它只会认为「我又猜错了」，
 * 于是退化成交空壳（实测踩过：提示词里写着不存在的 {@code block:"move"}）。
 */
class AcxBlockCatalogTest {

    /** 只读一个假端口：真名 goto，带必填可空参数与输出。 */
    private static AcxToolPort gotoPort() {
        return new AcxToolPort() {
            @Override
            public String name() {
                return "goto";
            }

            @Override
            public String description() {
                return "寻路移动到目标坐标。";
            }

            @Override
            public AcxPortSchema schema() {
                return AcxPortSchema.builder()
                        .allowUnknown(false)
                        .param("x", AcxPortSchema.Param.req(AcxPortSchema.Type.NUMBER).nullable())
                        .param("block", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING).desc("目标方块名"))
                        .param("type_filter", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING)
                                .withEnum("hostile", "passive"))
                        .output("final_x", "final_y", "task_id")
                        .build();
            }

            @Override
            public Result invoke(Map<String, Object> params) {
                return Result.completed(Map.of());
            }
        };
    }

    @Test
    void rendersNameParamsOutputsAndEnum() {
        String t = AcxBlockCatalog.render(List.of(gotoPort()), Map.of());

        assertTrue(t.contains("goto"), "真名必须在: " + t);
        assertTrue(t.contains("x:数字 必填"), "必填与类型要写清楚: " + t);
        assertTrue(t.contains("可为 null"), "可空是独立语义，不能省: " + t);
        assertTrue(t.contains("block:字符串 可选"), "可选要写清: " + t);
        assertTrue(t.contains("枚举="), "枚举值必须给出，否则 AI 只能猜: " + t);
        assertTrue(t.contains("final_x"), "输出字段必须给出（$prev.x 引用要靠它）: " + t);
    }

    @Test
    void listsAliasOnlyWhenItsTargetIsRegistered() {
        Map<String, String> aliases = Map.of("move_to", "goto", "mine_block", "mine");

        String withGoto = AcxBlockCatalog.render(List.of(gotoPort()), aliases);
        assertTrue(withGoto.contains("move_to"), "目标在册 ⇒ 别名要列出来（旧脚本靠它加载）: " + withGoto);
        assertFalse(withGoto.contains("mine_block"),
                "目标不在册 ⇒ 绝不能列它的别名（列了就是教 AI 用一个不存在的积木）: " + withGoto);
    }

    @Test
    void emptyRegistryRendersHeaderWithoutCrashing() {
        String t = AcxBlockCatalog.render(List.of(), Map.of());
        assertTrue(t.contains("可用积木"), "空表也要有表头，否则下游分不清「空」和「坏了」");
    }

    @Test
    void noAliasSectionWhenNoAliasIsUsable() {
        String t = AcxBlockCatalog.render(List.of(gotoPort()), Map.of("mine_block", "mine"));
        assertFalse(t.contains("别名"), "一个可用别名都没有时，不该出现空的别名小节: " + t);
    }
}
