package com.geoviz.terrain.evolution;

import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionSimulator;
import com.geoviz.terrain.noise.NoiseGenerator;
import com.geoviz.terrain.noise.NoiseParams;
import com.geoviz.terrain.snapshot.Snapshot;
import com.geoviz.terrain.snapshot.SnapshotStore;
import com.geoviz.terrain.steady.SteadyStateDetector;
import com.geoviz.terrain.validation.InvalidParameterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 演进状态管理的核心不变量：
 * 1. 整条链可复现：同一起始场 + 同参数序列（含种子）重放，每一帧的高度场
 *    与汇流场逐比特一致——「服务端记着状态」本身不引入任何随机差异；
 * 2. 分支隔离：从历史帧岔出新分支并在其上推进后，父链被岔出的那一帧及其后
 *    每一帧原封不动，帧数也不变；
 * 3. 每帧逐轮汇流场总和恰好等于该轮实际总步数；
 * 4. 不存在的链/帧、越界帧序号、非法侵蚀参数，都在动手计算前被带原因拒绝。
 */
class EvolutionServiceTest {

    private static final int RES = 48;

    private EvolutionService service;
    private SnapshotStore snapshotStore;
    private double[] initialTerrain;

    private static ErosionParams params(long seed, double erodeRate) {
        return new ErosionParams(3_000, erodeRate, 0.3, 0.02, 4.0, 1.5, 0.01, 0.05, 64, 0.01, seed);
    }

    @BeforeEach
    void setUp() {
        snapshotStore = new SnapshotStore();
        service = new EvolutionService(new ErosionSimulator(),
                new SteadyStateDetector(0.02), snapshotStore);
        initialTerrain = new NoiseGenerator()
                .generate(new NoiseParams(RES, 4, 2.0, 0.5, 3.0, 42L));
    }

    private EvolutionChain startChain() {
        return service.start(RES, initialTerrain, null);
    }

    /** 连续推进，返回每一轮用的参数序列（供重放复用）。 */
    private List<ErosionParams> advance(EvolutionChain chain, ErosionParams... sequence) {
        List<ErosionParams> used = new ArrayList<>();
        for (ErosionParams p : sequence) {
            service.advance(chain.id(), p);
            used.add(p);
        }
        return used;
    }

    private static double sum(double[] values) {
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return s;
    }

    // ---- 不变量一：整条链逐帧可复现 ----

    @Test
    void replayWithSameParamsAndSeedReproducesEveryFrameBitForBit() {
        EvolutionChain a = startChain();
        // 故意使用不同参数（含换种子、换侵蚀率），验证整条参数序列都被忠实重放
        List<ErosionParams> sequence = advance(a, params(7L, 0.3), params(11L, 0.5), params(7L, 0.15));

        // 重放：另一条链，从同一初始场开始，逐轮给同样的参数
        EvolutionChain b = service.start(RES, initialTerrain, null);
        for (ErosionParams p : sequence) {
            service.advance(b.id(), p);
        }

        assertEquals(a.frameCount(), b.frameCount());
        for (int i = 0; i < a.frameCount(); i++) {
            EvolutionFrame fa = a.frame(i);
            EvolutionFrame fb = b.frame(i);
            assertArrayEquals(fa.heightmap(), fb.heightmap(),
                    "第 " + i + " 帧高度场必须逐比特一致（重放同参数序列、同种子）");
            assertArrayEquals(fa.flow(), fb.flow(),
                    "第 " + i + " 帧汇流场必须逐比特一致（服务端记状态不得引入额外随机性）");
            assertEquals(fa.flowChange(), fb.flowChange(),
                    "第 " + i + " 帧的汇流场变化幅度必须一致");
        }
    }

    @Test
    void startCopiesInitialTerrainSoCallerMutationDoesNotCorruptFrameZero() {
        EvolutionChain chain = startChain();
        double original = initialTerrain[0];
        initialTerrain[0] = 123.456; // 调用方随后改写提交数组
        assertEquals(original, chain.frame(0).heightmap()[0], 0.0);
    }

    // ---- 不变量二：分支隔离 ----

    @Test
    void branchingAndAdvancingBranchLeavesParentChainUntouched() {
        EvolutionChain parent = startChain();
        advance(parent, params(7L, 0.3), params(7L, 0.3), params(9L, 0.4));
        assertEquals(4, parent.frameCount());

        // 岔出前把父链每一帧（含第 0 帧）整体留存
        double[][] parentHeightmaps = new double[parent.frameCount()][];
        double[][] parentFlows = new double[parent.frameCount()][];
        for (int i = 0; i < parent.frameCount(); i++) {
            parentHeightmaps[i] = parent.frame(i).heightmap().clone();
            parentFlows[i] = parent.frame(i).flow().clone();
        }

        // 从第 1 帧岔出新分支
        int branchPoint = 1;
        EvolutionChain branch = service.branch(parent.id(), branchPoint);
        assertTrue(branch.isBranch());
        assertEquals(parent.id(), branch.parentChainId());
        assertEquals(branchPoint, branch.parentFrameIndex());
        assertEquals(1, branch.frameCount());
        // 新分支第 0 帧地形 = 父链第 1 帧地形；但它是独立拷贝，汇流场为零（本链尚未冲刷）
        assertArrayEquals(parentHeightmaps[1], branch.frame(0).heightmap());
        assertEquals(0.0, sum(branch.frame(0).flow()), 0.0);
        assertNull(branch.frame(0).params());

        // 在新分支上用不同参数继续推进若干轮
        advance(branch, params(99L, 0.8), params(99L, 0.8));
        assertEquals(3, branch.frameCount());

        // 父链帧数不变，且被岔出的那一帧及其之后每一帧逐比特原样
        assertEquals(4, parent.frameCount());
        for (int i = 0; i < parent.frameCount(); i++) {
            assertArrayEquals(parentHeightmaps[i], parent.frame(i).heightmap(),
                    "分支推进后父链第 " + i + " 帧高度场必须原封不动");
            assertArrayEquals(parentFlows[i], parent.frame(i).flow(),
                    "分支推进后父链第 " + i + " 帧汇流场必须原封不动");
        }

        // 分支自己的血缘可被查询到
        EvolutionChain refetchedParent = service.getChain(parent.id());
        assertFalse(refetchedParent.isBranch());
        EvolutionChain refetchedBranch = service.getChain(branch.id());
        assertEquals(parent.id(), refetchedBranch.parentChainId());
        assertEquals(branchPoint, refetchedBranch.parentFrameIndex());
    }

    // ---- 不变量三：每帧汇流场总和 == 该轮实际总步数 ----

    @Test
    void everyAdvancedFrameFlowSumsToItsActualDropletSteps() {
        EvolutionChain chain = startChain();
        advance(chain, params(7L, 0.3), params(7L, 0.5), params(13L, 0.2));

        assertEquals(0.0, sum(chain.frame(0).flow()), 0.0, "起始帧汇流场应为零场");
        for (int i = 1; i < chain.frameCount(); i++) {
            EvolutionFrame frame = chain.frame(i);
            long actualSteps = frame.stats().totalSteps();
            assertEquals((double) actualSteps, sum(frame.flow()), 0.0,
                    "第 " + i + " 帧汇流场总和必须等于该轮雨滴实际走过的总步数 " + actualSteps);
            for (double v : frame.flow()) {
                assertTrue(v >= 0.0 && v == Math.rint(v), "流经次数必须是非负整数");
            }
        }
    }

    @Test
    void frameCarriesTheParamsThatProducedIt() {
        EvolutionChain chain = startChain();
        ErosionParams p1 = params(7L, 0.3);
        ErosionParams p2 = params(123L, 0.7);
        service.advance(chain.id(), p1);
        service.advance(chain.id(), p2);
        assertNull(chain.frame(0).params());
        assertEquals(p1, chain.frame(1).params());
        assertEquals(p2, chain.frame(2).params());
    }

    // ---- 非法用法在计算前被带原因拒绝 ----

    @Test
    void unknownChainAndOutOfRangeFramesAreRejected() {
        EvolutionChain chain = startChain();
        advance(chain, params(7L, 0.3));

        EvolutionNotFoundException e1 = assertThrows(EvolutionNotFoundException.class,
                () -> service.getChain("does-not-exist"));
        assertTrue(e1.getMessage().contains("演进链不存在"));

        assertThrows(EvolutionNotFoundException.class,
                () -> service.getFrame("does-not-exist", 0));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.advance("does-not-exist", params(7L, 0.3)));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.steadyStatus("does-not-exist"));

        // 帧序号越界（含超出末尾与负数）
        assertThrows(EvolutionNotFoundException.class,
                () -> service.getFrame(chain.id(), 42));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.getFrame(chain.id(), -1));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.branch(chain.id(), 42));

        int framesBefore = chain.frameCount();
        assertThrows(EvolutionNotFoundException.class,
                () -> service.advance("missing", params(7L, 0.3)));
        assertEquals(framesBefore, chain.frameCount(), "被拒绝的推进不得改变链状态");
    }

    @Test
    void invalidErosionParamsAreRejectedBeforeAnyComputation() {
        EvolutionChain chain = startChain();
        int frameCountBefore = chain.frameCount();
        double[] lastFrameBefore = chain.lastFrame().heightmap().clone();

        ErosionParams bad = new ErosionParams(0, -0.1, 0.3, 0.02, 4.0, 1.5, 0.01, 0.05, 64, 0.01, 7L);
        InvalidParameterException e = assertThrows(InvalidParameterException.class,
                () -> service.advance(chain.id(), bad));
        assertTrue(e.getMessage().contains("dropletCount") || e.getMessage().contains("erodeRate"));

        assertEquals(frameCountBefore, chain.frameCount(), "非法参数不得追加任何帧");
        assertArrayEquals(lastFrameBefore, chain.lastFrame().heightmap(),
                "非法参数不得改动当前帧");
    }

    @Test
    void startingWithIllegalHeightmapIsRejected() {
        assertThrows(InvalidParameterException.class,
                () -> service.start(RES, new double[3], null));
        double[] withNaN = new double[RES * RES];
        withNaN[5] = Double.NaN;
        assertThrows(InvalidParameterException.class,
                () -> service.start(RES, withNaN, null));
    }

    // ---- 任意帧导出为命名快照 ----

    @Test
    void exportAnyFrameAsNamedSnapshot() {
        EvolutionChain chain = startChain();
        advance(chain, params(7L, 0.3), params(9L, 0.4));

        Snapshot snapshot = service.exportFrame(chain.id(), 1, "frame-one");
        assertEquals("frame-one", snapshot.name());
        assertEquals(RES, snapshot.resolution());
        assertArrayEquals(chain.frame(1).heightmap(), snapshot.heightmap());
        assertEquals(params(7L, 0.3), snapshot.erosion());

        // 通过原有的快照存储可取回，且演进链不受影响
        Snapshot loaded = snapshotStore.find("frame-one").orElseThrow();
        assertArrayEquals(chain.frame(1).heightmap(), loaded.heightmap());
        assertEquals(3, chain.frameCount());

        assertThrows(InvalidParameterException.class,
                () -> service.exportFrame(chain.id(), 1, "  "));
        assertThrows(EvolutionNotFoundException.class,
                () -> service.exportFrame(chain.id(), 99, "x"));
    }
}
