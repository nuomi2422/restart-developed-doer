package com.dwinovo.numen.acx.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AC 脚本里的单个步骤。
 *
 * <p>每步引用一个已注册积木（{@code block}）或另一个 AC（同名），传入参数。
 * 当 {@code block} 是内置控制块 {@value #BLOCK_IF} / {@value #BLOCK_WHILE} 时，
 * {@code children} 是条件执行 / 循环执行的子步骤链；
 * 当 {@code block} 是内置守卫 {@value #BLOCK_GUARD} 时，本步不执行积木，
 * 只对 {@code params.conditions} 求值：满足则继续，不满足则按 {@code on_fail} 暂停/失败。</p>
 *
 * <p><b>不可变</b>：构造后 params 与 children 都是防御性拷贝的只读视图。</p>
 */
public final class AcxStep {

    public static final String BLOCK_IF = "if";
    public static final String BLOCK_WHILE = "while";
    /** 步骤级守卫：不调积木，只做前置检测（见 {@code AcxRunner.execGuard}）。 */
    public static final String BLOCK_GUARD = "guard";
    /** 变量写回步：{@code {"block":"set","params":{"名":值}}}（AC-B9）。 */
    public static final String BLOCK_SET = "set";
    /** AC-B11：遍历列表的控制块 {@code {"block":"for","params":{"list":"$scan.matches","as":"item"}}}。 */
    public static final String BLOCK_FOR = "for";

    private final String id;
    private final String block;
    private final Map<String, Object> params;
    private final List<AcxStep> children;

    public AcxStep(String id, String block, Map<String, Object> params) {
        this(id, block, params, null);
    }

    public AcxStep(String id, String block, Map<String, Object> params, List<AcxStep> children) {
        this.id = id;
        this.block = block;
        this.params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        this.children = children == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(children));
    }

    public String id() {
        return id;
    }

    public String block() {
        return block;
    }

    public Map<String, Object> params() {
        return params;
    }

    /** 控制块子步骤链；线性步骤为 {@code null}。 */
    public List<AcxStep> children() {
        return children;
    }

    public boolean isControl() {
        return BLOCK_IF.equals(block) || BLOCK_WHILE.equals(block) || BLOCK_FOR.equals(block);
    }

    public boolean isFor() {
        return BLOCK_FOR.equals(block);
    }

    public boolean isLoop() {
        // for 也是循环：熔断 / 停滞 / 断点三套机制要共用
        return BLOCK_WHILE.equals(block) || BLOCK_FOR.equals(block);
    }

    /** 步骤级守卫（内置，不查积木注册表，不接受 children）。 */
    /** AC-B9：变量写回步。内置块，不查积木表。 */
    public boolean isSet() {
        return BLOCK_SET.equals(block);
    }

    public boolean isGuard() {
        return BLOCK_GUARD.equals(block);
    }

    /**
     * 深度优先展开本步骤及其全部后代，返回新的列表。
     *
     * <p>用于「最大步数」静态估算与「字段引用」校验，避免两处各写一份递归。</p>
     */
    public List<AcxStep> flattenSelfAndDescendants() {
        List<AcxStep> out = new ArrayList<>();
        out.add(this);
        if (children != null) {
            for (AcxStep child : children) {
                out.addAll(child.flattenSelfAndDescendants());
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "AcxStep{id=" + id + ", block=" + block
                + ", children=" + (children == null ? 0 : children.size()) + "}";
    }
}
