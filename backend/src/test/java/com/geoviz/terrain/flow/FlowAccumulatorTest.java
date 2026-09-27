package com.geoviz.terrain.flow;

import com.geoviz.terrain.erosion.DropletListener;
import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionResult;
import com.geoviz.terrain.erosion.ErosionSimulator;
import com.geoviz.terrain.noise.NoiseGenerator;
import com.geoviz.terrain.noise.NoiseParams;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汇流量场测试：每颗雨滴每走一步恰好给当时所在格点贡献一次流经量，
 * 整场合计与侵蚀统计的实际总步数严格相等，且每个格点都是非负整数计数。
 */
class FlowAccumulatorTest {

    private static final int RES = 48;

    private final ErosionSimulator simulator = new ErosionSimulator();
    private final NoiseGenerator noiseGenerator = new NoiseGenerator();

    private double[] terrain() {
        return noiseGenerator.generate(new NoiseParams(RES, 4, 2.0, 0.5, 3.0, 42L));
    }

    private static ErosionParams params() {
        return new ErosionParams(5_000, 0.3, 0.3, 0.02, 4.0, 1.5, 0.01, 0.05, 64, 0.01, 7L);
    }

    private static double sum(double[] values) {
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return s;
    }

    @Test
    void flowFieldSumsExactlyToActualDropletSteps() {
        FlowAccumulator accumulator = new FlowAccumulator(RES);

        // 独立于 FlowAccumulator 的第二套计数，做真正的交叉核对（而非拿统计核对统计）
        AtomicLong countedSteps = new AtomicLong();
        double[] independentlyCounted = new double[RES * RES];
        DropletListener composite = new DropletListener() {
            @Override
            public void onStepPosition(int dropletIndex, int step, double posX, double posY) {
                accumulator.onStepPosition(dropletIndex, step, posX, posY);
                countedSteps.incrementAndGet();
                independentlyCounted[(int) posY * RES + (int) posX] += 1.0;
            }
        };

        ErosionResult result = simulator.erode(terrain(), RES, params(), composite);
        double[] flow = accumulator.flowField();

        // 三套总步数必须完全相等（整数量级，用 0 容差精确比较）
        long expectedSteps = result.stats().totalSteps();
        assertEquals(expectedSteps, countedSteps.get());
        assertEquals(expectedSteps, accumulator.totalSteps());
        assertEquals((double) expectedSteps, sum(flow), 0.0,
                "汇流场所有格点数值之和必须恰好等于这一轮雨滴实际走过的总步数");

        // 每个格点都是非负整数流经次数，且与独立计数逐格相同
        for (int i = 0; i < flow.length; i++) {
            assertTrue(flow[i] >= 0.0, "流经次数不能为负");
            assertEquals(Math.rint(flow[i]), flow[i], 0.0, "每个格点的流经次数必须是整数");
            assertEquals(independentlyCounted[i], flow[i], 0.0,
                    "每个格点的流经次数应与独立计数逐格一致");
        }
        assertTrue(expectedSteps > 0, "该参数下应确实有雨滴步进被记录");
    }
}
