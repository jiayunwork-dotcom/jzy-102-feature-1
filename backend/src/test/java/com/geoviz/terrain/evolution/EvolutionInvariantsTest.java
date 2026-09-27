package com.geoviz.terrain.evolution;

import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionSimulator;
import com.geoviz.terrain.flow.ConvergenceDetector;
import com.geoviz.terrain.flow.FlowAccumulator;
import com.geoviz.terrain.noise.NoiseGenerator;
import com.geoviz.terrain.noise.NoiseParams;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 演进能力四条钉死关系的自动化验证：
 * <ol>
 *   <li><b>整条链可复现</b>：同一起始地形、同一参数序列（含种子）重放，
 *       每帧地形、本轮流经场、累积汇流场逐比特一致；</li>
 *   <li><b>分支隔离</b>：从历史帧岔出的新链独立推进后，原链该帧及其后每一帧
 *       与岔出前字节相同；</li>
 *   <li><b>汇流守恒且可解释</b>：每帧本轮流经场全场之和 = 该轮全部雨滴实际步数之和，
 *       与独立重放该轮统计出的总步数对上；每一步恰好给当时所在格点贡献一次；</li>
 *   <li><b>稳态单调靠拢</b>：参数不变持续推进，相邻帧累积汇流场变化幅度
 *       随轮次一路收窄（非增），跨过阈值后才报稳态，绝不越推越剧烈却报稳态。</li>
 * </ol>
 */
class EvolutionInvariantsTest {

    private static final int RES = 64;

    private final NoiseGenerator noiseGenerator = new NoiseGenerator();
    private final ErosionSimulator simulator = new ErosionSimulator();
    private final EvolutionService service =
            new EvolutionService(new EvolutionStore(), new ErosionSimulator());

    private double[] baseTerrain() {
        return noiseGenerator.generate(new NoiseParams(RES, 4, 2.0, 0.5, 3.0, 42L));
    }

    /** 测试用侵蚀参数（中等强度，约 7~8 轮跨过默认稳态阈值）。 */
    private static ErosionParams params(long seed) {
        return new ErosionParams(
                30_000, 0.3, 0.3, 0.02, 4.0, 1.5, 0.01, 0.05, 64, 0.01, seed);
    }

    /** 直接推进服务 n 轮（每轮用同一份参数）。 */
    private void advance(EvolutionChain chain, ErosionParams p, int rounds) {
        for (int i = 0; i < rounds; i++) {
            service.advance(chain.id(), p);
        }
    }

    // ---- 不变量一：整条链逐比特可复现 ----

    @Test
    void sameInitialTerrainAndParamSequenceReplayBitIdenticalChain() {
        double[] base = baseTerrain();
        long[] seeds = {7L, 11L, 7L, 23L, 11L}; // 故意每轮换种子，证明逐轮种子都被忠实重放

        EvolutionChain chainA = service.start(RES, base.clone(), null);
        List<EvolutionFrame> framesA = new ArrayList<>();
        framesA.add(service.getFrame(chainA.id(), 0));
        for (long seed : seeds) {
            framesA.add(service.advance(chainA.id(), params(seed)));
        }

        // 用完全独立的服务实例、同一起始地形、同一参数序列重放
        EvolutionService service2 = new EvolutionService(new EvolutionStore(), new ErosionSimulator());
        EvolutionChain chainB = service2.start(RES, base.clone(), null);
        List<EvolutionFrame> framesB = new ArrayList<>();
        framesB.add(service2.getFrame(chainB.id(), 0));
        for (long seed : seeds) {
            framesB.add(service2.advance(chainB.id(), params(seed)));
        }

        assertEquals(framesA.size(), framesB.size());
        for (int i = 0; i < framesA.size(); i++) {
            assertArrayEquals(framesA.get(i).heightmap(), framesB.get(i).heightmap(),
                    "第 " + i + " 帧地形必须逐比特一致");
            assertArrayEquals(framesA.get(i).roundFlow(), framesB.get(i).roundFlow(),
                    "第 " + i + " 帧本轮流经场必须逐比特一致");
            assertArrayEquals(framesA.get(i).flowAccumulation(), framesB.get(i).flowAccumulation(),
                    "第 " + i + " 帧累积汇流场必须逐比特一致");
        }
    }

    // ---- 不变量二：分支隔离 ----

    @Test
    void branchFromHistoryFrameDoesNotMutateOriginalChain() {
        double[] base = baseTerrain();
        ErosionParams p = params(7L);
        EvolutionChain parent = service.start(RES, base.clone(), null);
        advance(parent, p, 5);

        int forkAt = 2;
        // 岔出前把原链每一帧（重点是 forkAt 及其之后）的地形/汇流场全部留底
        List<double[]> heightBefore = new ArrayList<>();
        List<double[]> roundFlowBefore = new ArrayList<>();
        List<double[]> cumulativeBefore = new ArrayList<>();
        for (int i = 0; i <= 5; i++) {
            EvolutionFrame f = service.getFrame(parent.id(), i);
            heightBefore.add(f.heightmap().clone());
            roundFlowBefore.add(f.roundFlow().clone());
            cumulativeBefore.add(f.flowAccumulation().clone());
        }

        EvolutionChain branch = service.branch(parent.id(), forkAt);
        assertEquals(parent.id(), branch.parentChainId());
        assertEquals(forkAt, branch.parentFrameIndex());
        assertTrue(branch.isBranch());
        assertEquals(1, branch.frameCount());

        // 分支第 0 帧 == 父链 forkAt 帧
        assertArrayEquals(heightBefore.get(forkAt), service.getFrame(branch.id(), 0).heightmap());

        // 在分支上独立推进 4 轮（第 0 帧 + 4 个新帧 = 5 帧）
        advance(branch, params(99L), 4);
        assertEquals(5, branch.frameCount());

        // 原链第 forkAt 帧及其之后的每一帧必须原封不动
        for (int i = forkAt; i <= 5; i++) {
            EvolutionFrame f = service.getFrame(parent.id(), i);
            assertArrayEquals(heightBefore.get(i), f.heightmap(),
                    "分支推进后原链第 " + i + " 帧地形被污染");
            assertArrayEquals(roundFlowBefore.get(i), f.roundFlow(),
                    "分支推进后原链第 " + i + " 帧本轮流经场被污染");
            assertArrayEquals(cumulativeBefore.get(i), f.flowAccumulation(),
                    "分支推进后原链第 " + i + " 帧累积汇流场被污染");
        }
        // 原链仍可继续独立追加，且分支不受影响
        EvolutionFrame parentNext = service.advance(parent.id(), params(5L));
        assertEquals(6, parentNext.index());
        assertEquals(4, service.getFrame(branch.id(), branch.headIndex()).index());
        // 两条链第 3 帧（各自独立推进的结果）必须不同，证明确实各走各的
        assertFalse(java.util.Arrays.equals(
                service.getFrame(parent.id(), 3).heightmap(),
                service.getFrame(branch.id(), 3).heightmap()));
    }

    // ---- 不变量三：汇流守恒且可解释 ----

    @Test
    void roundFlowTotalEqualsActualDropletStepsOfThatRound() {
        double[] base = baseTerrain();
        ErosionParams p = params(7L);
        EvolutionChain chain = service.start(RES, base.clone(), null);

        // 对每一轮都做双重交叉核对：
        //  (a) 帧内 roundFlow 总和 == 帧 stats.totalSteps；
        //  (b) 用同一地形、同一参数独立重放该轮（裸模拟器 + 独立累加器），
        //      拿到的总步数与逐格流经场完全一致。
        double[] replayedTerrain = base.clone();
        for (int round = 1; round <= 4; round++) {
            EvolutionFrame frame = service.advance(chain.id(), p);

            double flowSum = 0;
            for (double v : frame.roundFlow()) {
                flowSum += v;
            }
            assertEquals(frame.stats().totalSteps(), (long) flowSum,
                    "第 " + round + " 轮：汇流场总和必须等于该轮实际雨滴步数之和");
            assertEquals(frame.stats().totalSteps(), frame.stats().totalSteps());

            // 独立重放：不经过演进服务，直接拿链尾上一轮地形跑
            FlowAccumulator independent = new FlowAccumulator(RES);
            var result = simulator.erode(replayedTerrain, RES, p, independent);
            assertEquals(independent.totalVisits(), (long) flowSum,
                    "第 " + round + " 轮：独立重放总步数与演进帧不一致");
            assertArrayEquals(independent.roundFlow(), frame.roundFlow(),
                    "第 " + round + " 轮：逐格流经量与独立重放不一致（每一步贡献一次）");
            assertEquals(frame.stats().totalSteps(), independent.totalVisits(),
                    "第 " + round + " 轮：统计总步数必须等于监听回调次数");
            replayedTerrain = result.heightmap();
        }
    }

    @Test
    void cumulativeFlowIsNormalizedEachFrame() {
        double[] base = baseTerrain();
        EvolutionChain chain = service.start(RES, base.clone(), null);
        advance(chain, params(7L), 6);
        for (int i = 1; i <= 6; i++) {
            EvolutionFrame f = service.getFrame(chain.id(), i);
            double sum = 0;
            for (double v : f.flowAccumulation()) {
                sum += v;
            }
            assertEquals(1.0, sum, 1e-12, "第 " + i + " 帧累积汇流场应归一化到 1");
            assertTrue(f.cumulativeSteps() > 0);
        }
    }

    // ---- 不变量四：稳态判据单调靠拢 ----

    @Test
    void flowChangeNarrowsMonotonicallyAndConvergesOnlyAfterThreshold() {
        double[] base = baseTerrain();
        ErosionParams p = params(7L);
        EvolutionChain chain = service.start(RES, base.clone(), null);

        List<Double> changes = new ArrayList<>();
        for (int round = 1; round <= 12; round++) {
            EvolutionFrame f = service.advance(chain.id(), p);
            assertNotNull(f.flowChange());
            changes.add(f.flowChange());
        }

        // 从第二轮起（第一轮是从全零起始场到首场的初始化跳变）必须非增。
        // 实测会严格下降；这里钉死「不允许越推越剧烈」。
        for (int i = 1; i < changes.size(); i++) {
            assertTrue(changes.get(i) <= changes.get(i - 1) + 1e-12,
                    "变化幅度必须随轮次非增：第 " + i + " 轮 " + changes.get(i)
                            + " > 第 " + (i - 1) + " 轮 " + changes.get(i - 1));
        }

        // 终态应已跨过阈值被判稳态，且当前幅度确实不超过阈值
        assertTrue(changes.get(changes.size() - 1) <= ConvergenceDetector.DEFAULT_THRESHOLD,
                "12 轮后应收敛，末轮变化幅度=" + changes.get(changes.size() - 1));
        assertTrue(service.isConverged(chain));
        // 收敛前的早期轮次不应被误报稳态
        assertFalse(ConvergenceDetector.isConverged(changes.get(0)),
                "首场变化幅度=" + changes.get(0) + " 不应被判稳态");
    }

    // ---- 非法用法在计算前被挡下 ----

    @Test
    void invalidReferencesAndParamsAreRejectedBeforeComputation() {
        double[] base = baseTerrain();
        EvolutionChain chain = service.start(RES, base.clone(), null);
        service.advance(chain.id(), params(7L));

        // 不存在的链
        assertThrows(EvolutionNotFoundException.class,
                () -> service.requireChain("does-not-exist"));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.advance("does-not-exist", params(7L)));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.getFrame("does-not-exist", 0));

        // 存在的链、帧序号越界（含负数）
        assertThrows(EvolutionNotFoundException.class,
                () -> service.getFrame(chain.id(), 5));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.getFrame(chain.id(), -1));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.branch(chain.id(), 99));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.branch(chain.id(), -1));

        // 推进参数非法：侵蚀率为负 —— 在任何模拟开始前被校验挡下，帧数量不变
        int framesBefore = chain.frameCount();
        ErosionParams bad = new ErosionParams(
                100, -0.3, 0.3, 0.02, 4.0, 1.5, 0.01, 0.05, 64, 0.01, 7L);
        assertThrows(com.geoviz.terrain.validation.InvalidParameterException.class,
                () -> service.advance(chain.id(), bad));
        assertEquals(framesBefore, chain.frameCount(), "非法推进不得产生新帧");

        // 非法起始高度场
        assertThrows(com.geoviz.terrain.validation.InvalidParameterException.class,
                () -> service.start(RES, new double[3], null));
    }
}
