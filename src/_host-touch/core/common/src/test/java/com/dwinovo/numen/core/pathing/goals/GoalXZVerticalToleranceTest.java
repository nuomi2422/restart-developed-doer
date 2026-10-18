package com.dwinovo.numen.core.pathing.goals;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalXZ 垂直容差契约（2026-09-28）。
 *
 * <p>实机 bug：{@code goto} 只给 x+z（COLUMN，按契约「Y 解析到地表」）时，旧 GoalXZ
 * 连到达判定都不看 Y —— 站在丛林树冠 y=62 对 y=47 的地面目标直接判「已到达」
 * （日志 {@code ARRIVED-IN-PLACE feet=425,62,-305 goal-center=424,47,-301}），
 * 于是「到了」→被派下一个相邻目标→<strong>原地左右横跳且任务永不完成</strong>。
 *
 * <p>这些用例钉住两件事：给了容差就不能在容差外判到达；没给容差的老调用方行为逐字不变。
 */
class GoalXZVerticalToleranceTest {

    @Test void legacyTwoArgGoalKeepsAnyHeightSemantics() {
        GoalXZ g = new GoalXZ(424, -301);
        assertFalse(g.verticalConstrained());
        assertTrue(g.isInGoal(424, 47, -301));
        assertTrue(g.isInGoal(424, 62, -301));   // 老语义：任意高度都算到
        assertTrue(g.isInGoal(424, -200, -301));
        assertFalse(g.isInGoal(425, 47, -301));
    }

    @Test void canopyIsNotTheGround() {
        // 树冠 y=62 不等于地面 y=47 的目标列
        GoalXZ ground = new GoalXZ(424, -301, 47, 2);
        assertTrue(ground.verticalConstrained());
        assertFalse(ground.isInGoal(424, 62, -301));
        assertTrue(ground.isInGoal(424, 47, -301));
        assertTrue(ground.isInGoal(424, 48, -301));   // 容差内
        assertTrue(ground.isInGoal(424, 45, -301));
        assertTrue(ground.isInGoal(424, 49, -301));   // 边界含：正好差 2
        assertFalse(ground.isInGoal(424, 50, -301));  // 差 3 出容差
    }

    @Test void horizontalStillRequired() {
        GoalXZ g = new GoalXZ(10, 20, 64, 2);
        assertFalse(g.isInGoal(11, 64, 20));
        assertFalse(g.isInGoal(10, 64, 21));
        assertTrue(g.isInGoal(10, 64, 20));
    }

    @Test void negativeToleranceIsClampedToZero() {
        GoalXZ g = new GoalXZ(0, 0, 64, -5);
        assertTrue(g.isInGoal(0, 64, 0));
        assertFalse(g.isInGoal(0, 65, 0));
    }

    @Test void toStringShowsVerticalConstraintForDebugging() {
        assertEquals("GoalXZ{x=1,z=2}", new GoalXZ(1, 2).toString());
        assertTrue(new GoalXZ(1, 2, 64, 2).toString().contains("refY=64"));
    }
}
