package com.geoviz.terrain.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汇流与稳态判定模块的单元测试：
 * 单轮流经守恒、累积场归一化、稳态阈值边界与基本的收窄趋势。
 */
class FlowModuleTest {

    private static final int RES = 8;

    @Test
    void roundFlowSumEqualsTraverseCount() {
        FlowAccumulator acc = new FlowAccumulator(RES);
        // 手工模拟 3 条轨迹，共 3 + 2 + 1 = 6 步
        acc.onTraverse(0, 0, 1, 1);
        acc.onTraverse(0, 1, 2, 1);
        acc.onTraverse(0, 2, 2, 2);
        acc.onTraverse(1, 0, 5, 5);
        acc.onTraverse(1, 1, 5, 4);
        acc.onTraverse(2, 0, 0, 0);

        double sum = 0;
        for (double v : acc.roundFlow()) {
            sum += v;
        }
        assertEquals(6, acc.totalVisits());
        assertEquals(6.0, sum, 0.0);
        // 被经过两次的格点（本数据里没有同格重复）——补一个重复落点
        FlowAccumulator acc2 = new FlowAccumulator(RES);
        acc2.onTraverse(0, 0, 3, 3);
        acc2.onTraverse(1, 0, 3, 3);
        assertEquals(2.0, acc2.roundFlow()[3 * RES + 3], 0.0);
        assertEquals(2, acc2.totalVisits());
    }

    @Test
    void cumulativeFieldIsNormalizedAndConservesMass() {
        FlowField field = FlowField.empty(RES);
        double[] round1 = new double[RES * RES];
        round1[0] = 40;
        round1[1] = 60; // 本轮共 100 步
        double[] snap1 = field.addRound(round1, 100);
        assertEquals(1.0, sum(snap1), 1e-12);
        assertEquals(0.4, snap1[0], 1e-12);
        assertEquals(0.6, snap1[1], 1e-12);
        assertEquals(100, field.totalSteps());

        double[] round2 = new double[RES * RES];
        round2[0] = 100; // 第二轮 100 步全部过格点 0
        double[] snap2 = field.addRound(round2, 100);
        // 累积：格点0 得 140/200=0.7，格点1 得 60/200=0.3
        assertEquals(1.0, sum(snap2), 1e-12);
        assertEquals(0.7, snap2[0], 1e-12);
        assertEquals(0.3, snap2[1], 1e-12);
        assertEquals(200, field.totalSteps());

        // 快照是独立数组，再追加一轮不改变已取走的 snap2
        double[] round3 = new double[RES * RES];
        round3[2] = 100;
        field.addRound(round3, 100);
        assertEquals(0.7, snap2[0], 1e-12);
    }

    @Test
    void branchedFlowFieldIsIndependentCopy() {
        FlowField field = FlowField.empty(RES);
        double[] round = new double[RES * RES];
        round[0] = 100;
        double[] snap = field.addRound(round, 100);

        FlowField branched = FlowField.from(snap, 100);
        double[] more = new double[RES * RES];
        more[1] = 100;
        branched.addRound(more, 100);

        // 原场快照不被分支上的追加影响
        double[] originalAgain = field.snapshot();
        assertArrayEquals(snap, originalAgain, 1e-12);
        assertEquals(100, field.totalSteps());
        assertEquals(200, branched.totalSteps());
    }

    @Test
    void changeMagnitudeIsL1AndThresholdBoundaryInclusive() {
        double[] a = {0.5, 0.5, 0.0};
        double[] b = {0.25, 0.25, 0.5};
        // |0.25| + |0.25| + |0.5| = 1.0
        assertEquals(1.0, ConvergenceDetector.changeMagnitude(a, b), 1e-12);
        assertEquals(0.0, ConvergenceDetector.changeMagnitude(a, a), 1e-12);

        // 阈值是「低于或等于」：恰好等于阈值算稳态
        double atThreshold = ConvergenceDetector.DEFAULT_THRESHOLD;
        assertTrue(ConvergenceDetector.isConverged(atThreshold));
        assertFalse(ConvergenceDetector.isConverged(atThreshold + 1e-9));
    }

    @Test
    void mismatchedLengthsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ConvergenceDetector.changeMagnitude(new double[4], new double[9]));
        assertThrows(IllegalArgumentException.class,
                () -> new FlowAccumulator(0));
        assertThrows(IllegalArgumentException.class,
                () -> FlowField.from(new double[5], 10)); // 长度不是平方数
    }

    private static double sum(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return s;
    }
}
