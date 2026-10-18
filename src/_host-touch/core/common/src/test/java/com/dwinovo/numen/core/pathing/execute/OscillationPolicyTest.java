package com.dwinovo.numen.core.pathing.execute;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「原地横跳」判据契约（2026-09-28）。
 *
 * <p>实测形态：规划路不进水，但身体走进水→自动上浮→再走进水→再上浮；
 * 或在树/墙边来回侧移。路径层不报错、卡死检测也不认，能这样磨几十分钟。
 * 这些用例把"该熔断"与"不该误伤正常绕行"的边界钉死。
 */
class OscillationPolicyTest {

    private static List<double[]> zigzag(double amp, int n, double step) {
        List<double[]> s = new ArrayList<>();
        double x = 0;
        for (int i = 0; i < n; i++) {
            s.add(new double[]{x, 0});
            x += (i % 2 == 0 ? step : -step);   // 原地左右摆
        }
        return s;
    }

    @Test void detectsBackAndForthWithNoNetProgress() {
        assertTrue(OscillationPolicy.oscillating(zigzag(0, 14, 1.5),
                12, 3.0, 10.0, 120.0, 4));
    }

    @Test void honestForwardProgressIsNotOscillation() {
        // 一路向前：净位移大 → 绝不误伤
        List<double[]> s = new ArrayList<>();
        for (int i = 0; i < 14; i++) s.add(new double[]{i * 1.5, 0});
        assertFalse(OscillationPolicy.oscillating(s, 12, 3.0, 10.0, 120.0, 4));
    }

    @Test void longDetourThatKeepsGoingIsNotOscillation() {
        // 大范围绕行：路程大、净位移小，但方向始终朝前 → 不该熔断（否则会误杀绕路）
        List<double[]> s = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double a = Math.PI * 0.5 * (i / 19.0);   // 绕大半圈
            s.add(new double[]{Math.cos(a) * 12, Math.sin(a) * 12});
        }
        assertFalse(OscillationPolicy.oscillating(s, 12, 3.0, 10.0, 120.0, 4));
    }

    @Test void standingStillIsNotOscillation() {
        // 完全不动（零路程）不是"横跳"，那是卡死，由卡死检测管
        List<double[]> s = new ArrayList<>();
        for (int i = 0; i < 14; i++) s.add(new double[]{5, 5});
        assertFalse(OscillationPolicy.oscillating(s, 12, 3.0, 10.0, 120.0, 4));
    }

    @Test void tooFewSamplesMeansNoVerdict() {
        assertFalse(OscillationPolicy.oscillating(zigzag(0, 5, 1.5), 12, 3.0, 10.0, 120.0, 4));
        assertFalse(OscillationPolicy.oscillating(null, 12, 3.0, 10.0, 120.0, 4));
    }
}
