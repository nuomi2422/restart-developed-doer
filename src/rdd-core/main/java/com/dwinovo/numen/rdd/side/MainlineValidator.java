package com.dwinovo.numen.rdd.side;

/**
 * 主线恢复令牌的校验口：把令牌拿到"活的"主线（TaskChain）上核对。
 *
 * <p>不满足（主线没了 / 目标换了 / 计划代次变了 / 当前二级已合法推进）→ 返回 {@code null}，
 * 引擎据此"结束支线但不恢复旧引用"。
 */
public interface MainlineValidator {

    MainlineResumeToken validate(MainlineResumeToken token);
}
