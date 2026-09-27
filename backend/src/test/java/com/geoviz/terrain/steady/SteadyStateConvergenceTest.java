package com.geoviz.terrain.steady;

import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionSimulator;
import com.geoviz.terrain.evolution.EvolutionChain;
import com.geoviz.terrain.evolution.EvolutionFrame;
import com.geoviz.terrain.evolution.EvolutionService;
import com.geoviz.terrain.noise.NoiseGenerator;
import com.geoviz.terrain.noise.NoiseParams;
import com.geoviz.terrain.snapshot.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 稳态判据不变量：参数不变地持续推进时，累计汇流场的相邻帧变化幅度
 * 随轮次一路收窄、跨过阈值后被判稳态；且「是否稳态」与「变化幅度是否
 * 在阈值之下」严格一致——绝不能出现越推越剧烈却仍报稳态的矛盾。
 */
class SteadyStateConvergenceTest {

    private static final int RES = 64;
    private static final double THRESHOLD = 0.02;
    private static final int ROUNDS = 15;

    private EvolutionService newService(double threshold) {
        return new EvolutionService(new ErosionSimulator(),
                new SteadyStateDetector(threshold), new SnapshotStore());
    }

    private static ErosionParams fixedParams() {
        // 参数序列中每一轮完全相同（含种子）：雨滴落点逐轮一致，
        // 变化只来自地形本身的演化
        return new ErosionParams(20_000, 0.3, 0.3, 0.02, 4.0, 1.5, 0.01, 0.05, 64, 0.01, 7L);
    }

    @Test
    void flowChangeNarrowsMonotonicallyAndCrossesThresholdOnce() {
        // 用高阈值检测器开启链：确保「尚未冲刷、没有变化幅度」时不会被误判稳态
        EvolutionService service = newService(THRESHOLD);
        double[] terrain = new NoiseGenerator()
                .generate(new NoiseParams(RES, 4, 2.0, 0.5, 3.0, 42L));
        EvolutionChain chain = service.start(RES, terrain, null);
        assertNull(service.steadyStatus(chain.id()).change());
        assertFalse(service.steadyStatus(chain.id()).steady());

        ErosionParams params = fixedParams();
        List<Double> changes = new ArrayList<>();
        List<Boolean> steadyFlags = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            EvolutionService.AdvanceResult result = service.advance(chain.id(), params);
            changes.add(result.frame().flowChange());
            steadyFlags.add(result.steady().steady());
        }

        // 起始帧没有变化幅度（检测器对 null 变化幅度必须判为未稳态）
        EvolutionFrame frame0 = service.getFrame(chain.id(), 0);
        assertNull(frame0.flowChange());
        assertFalse(new SteadyStateDetector(THRESHOLD).assess(null).steady());

        // 找到第一次跨过阈值的轮次（列表下标 k 对应第 k+1 帧）
        int firstCross = -1;
        for (int i = 0; i < changes.size(); i++) {
            assertNotNull(changes.get(i));
            if (changes.get(i) <= THRESHOLD) {
                firstCross = i;
                break;
            }
        }
        assertTrue(firstCross > 0,
                "固定参数持续推进下变化幅度应收敛并跨过阈值 " + THRESHOLD
                        + "，实际序列: " + changes);

        // 跨过阈值之前：一路收窄，严格不增反降
        for (int i = 1; i <= firstCross; i++) {
            assertTrue(changes.get(i) <= changes.get(i - 1),
                    "跨过阈值前变化幅度必须单调不增：第 " + i + " 帧=" + changes.get(i - 1)
                            + "，第 " + (i + 1) + " 帧=" + changes.get(i));
        }

        // 跨过阈值之后：变化幅度不再上穿阈值，稳态判定一旦给出就保持
        for (int i = firstCross; i < changes.size(); i++) {
            assertTrue(changes.get(i) <= THRESHOLD,
                    "跨过阈值后变化幅度不应再上穿阈值：第 " + (i + 1) + " 帧=" + changes.get(i));
            assertTrue(steadyFlags.get(i),
                    "变化幅度已在阈值之下，必须报告稳态：第 " + (i + 1) + " 帧=" + changes.get(i));
        }

        // 自相矛盾检查：每一帧 steady ⟺ change ≤ threshold
        for (int i = 0; i < changes.size(); i++) {
            boolean below = changes.get(i) <= THRESHOLD;
            assertTrue(steadyFlags.get(i) == below,
                    "稳态结论与变化幅度矛盾：第 " + (i + 1) + " 帧 change=" + changes.get(i)
                            + "，steady=" + steadyFlags.get(i));
        }

        // 总体收窄：末端变化幅度必须远小于首轮（给出明确的收敛量级）
        assertTrue(changes.get(changes.size() - 1) < changes.get(0) / 20.0,
                "末端变化幅度应远小于首轮以体现真正收窄：首帧=" + changes.get(0)
                        + "，末帧=" + changes.get(changes.size() - 1));

        // 服务端汇总查询与阈值一起把「还差多少」交代清楚
        SteadyState status = service.steadyStatus(chain.id());
        assertTrue(status.steady());
        assertTrue(status.change() <= THRESHOLD);
        assertEquals(THRESHOLD, status.threshold(), 0.0);
    }
}
