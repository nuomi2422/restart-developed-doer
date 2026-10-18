package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxGameThreadGate;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolSchema;

import java.util.Map;
import java.util.Objects;

/**
 * 把积木包进游戏主线程门（ACX-G1）的装饰器。
 *
 * <p>DD 里所有碰世界状态的读取都强制走 {@code WorldReadGuard}；ACX 把它抽成了
 * {@link AcxGameThreadGate} 这个函数式契约。宿主注册积木时用本类包一层，
 * 积木本体就不用关心自己跑在哪个线程上。</p>
 *
 * <p>语义：</p>
 * <ul>
 *   <li>门为 {@code null} → {@link #of} 直接返回原积木（宿主未接门时不做无谓包装）；</li>
 *   <li>门抛异常 → {@code FAIL}，消息带积木名（不冒泡出执行器）；</li>
 *   <li>门返回 {@code null} → {@code FAIL}（调用没完成就返回，视同坏积木）。</li>
 * </ul>
 */
public final class AcxGatedTool implements AcxTool {

    private final AcxTool inner;
    private final AcxGameThreadGate gate;

    private AcxGatedTool(AcxTool inner, AcxGameThreadGate gate) {
        this.inner = inner;
        this.gate = gate;
    }

    /**
     * 包装一个积木。{@code gate} 为 null 时原样返回 {@code inner}（不引入额外一层）。
     *
     * @throws IllegalArgumentException inner 为 null
     */
    public static AcxTool of(AcxTool inner, AcxGameThreadGate gate) {
        Objects.requireNonNull(inner, "被包装的积木不能为 null");
        if (gate == null) {
            return inner;
        }
        return new AcxGatedTool(inner, gate);
    }

    @Override
    public String name() {
        return inner.name();
    }

    @Override
    public AcxToolSchema schema() {
        return inner.schema();
    }

    @Override
    public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
        try {
            AcxStepOutcome r = gate.call(() -> inner.execute(params, ctx));
            if (r == null) {
                return AcxStepOutcome.failed("游戏线程门返回了 null: " + inner.name());
            }
            return r;
        } catch (Throwable t) {
            // 门里的异常不能冒泡出执行器 —— 统一降级为 FAIL，消息带积木名便于定位
            return AcxStepOutcome.failed("游戏线程门执行失败: " + inner.name() + " - " + t);
        }
    }

    @Override
    public String toString() {
        return "AcxGatedTool(" + inner.name() + ")";
    }
}
