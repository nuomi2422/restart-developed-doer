package com.dwinovo.numen.acx.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AC 级前置条件 —— 决定「这条 AC 现在能不能开始跑」的环境声明。
 *
 * <p>与 FAIL 的语义区分是刻意的：</p>
 * <ul>
 *   <li><b>不满足 → PAUSED（可续）</b>：环境没就绪不是「这条 AC 做不到」，
 *       而是「现在还不该开始」。断点留在第 0 步，环境补齐后
 *       {@code AcxRunner.resume(def, prior, newInput)} 原地重评，
 *       不需要迁移、不需要重发整条 AC。</li>
 *   <li>FAIL 留给「工具真失败 / 验证不通过」，那条路走重试与换工具。</li>
 * </ul>
 *
 * <p>写法（{@code .ac} 顶层键 {@code preconditions}）：</p>
 * <pre>
 * "preconditions": [
 *   {"field":"$input.ready", "op":"==", "value":true,
 *    "message":"环境未就绪", "hint":"确认背包有空位后带新 input 重试"}
 * ]
 * </pre>
 *
 * <p>{@code field}/{@code value} 支持与条件求值同样的引用规则。执行器在起跑前
 * （{@code startIndex == 0}）求值，全部满足才进入步骤链。</p>
 */
public record AcxPrecondition(AcxCondition condition, String message, String hint) {

    public AcxPrecondition {
        if (condition == null) {
            throw new IllegalArgumentException("precondition 必须带条件");
        }
        message = message == null ? "" : message;
        hint = hint == null ? "" : hint;
    }

    public static AcxPrecondition of(AcxCondition condition) {
        return new AcxPrecondition(condition, "", "");
    }

    /** 结构化视图，供指纹与监测台输出。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", condition.field());
        m.put("op", condition.op().symbol());
        m.put("value", condition.value());
        if (!message.isEmpty()) {
            m.put("message", message);
        }
        if (!hint.isEmpty()) {
            m.put("hint", hint);
        }
        return m;
    }

    @Override
    public String toString() {
        return condition.toString();
    }
}
