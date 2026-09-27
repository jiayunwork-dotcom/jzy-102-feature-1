package com.geoviz.terrain.evolution;

import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionResult;
import com.geoviz.terrain.erosion.ErosionSimulator;
import com.geoviz.terrain.flow.ConvergenceDetector;
import com.geoviz.terrain.flow.FlowAccumulator;
import com.geoviz.terrain.flow.FlowField;
import com.geoviz.terrain.noise.NoiseParams;
import com.geoviz.terrain.validation.ParameterValidator;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 侵蚀演进编排模块：在 {@link EvolutionStore} 的链/帧结构之上，驱动一次完整推进——
 * <pre>
 *   取链尾当前地形
 *     → 在其上跑一轮侵蚀，同时用 {@link FlowAccumulator} 逐格点记录本轮流经次数
 *     → 把本轮数据并入链上持续维护的 {@link FlowField}（累积汇流场）
 *     → 用 {@link ConvergenceDetector} 比较相邻两帧、判定河网稳态
 *     → 地形 + 两张汇流场 + 参数 + 统计打包成新帧追加到链尾
 * </pre>
 *
 * <p>这是唯一把侵蚀模拟器、汇流计算、稳态判定三个模块接在一起的地方，
 * 演进状态管理（{@link EvolutionStore}）本身对算法一无所知。
 *
 * <h2>可复现性如何在整条链上成立</h2>
 * 每次推进只从链尾帧读出地形，侵蚀的全部随机性来自该帧请求里的
 * {@link ErosionParams#seed()}；模拟器单线程执行，不读取任何链外可变状态。
 * 因此「同一起始地形 + 同一参数序列（含种子）」的两条链，每一轮的高度场、
 * 本轮流经场与累积汇流场都逐比特一致——服务端记住状态只是在帧间传递地形，
 * 没有引入任何不受种子控制的差异。
 *
 * <h2>分支隔离</h2>
 * 分支复制来源帧的地形与累积汇流场、各自从副本继续演进；原链在分支过程中
 * 只读不写，之后的追加只进新链，原链每一帧字节不变。
 *
 * <h2>非法用法的拦截顺序</h2>
 * 任何计算开始之前先：校验参数 → 确认链存在 → 确认帧序号在范围内，
 * 全部通过才模拟。引用不存在的链/帧直接抛 {@link EvolutionNotFoundException}。
 */
@Service
public class EvolutionService {

    private final EvolutionStore store;
    private final ErosionSimulator simulator;

    public EvolutionService(EvolutionStore store, ErosionSimulator simulator) {
        this.store = store;
        this.simulator = simulator;
    }

    /** 用一张起始高度场开启一条根演进链。 */
    public EvolutionChain start(int resolution, double[] initialHeightmap, NoiseParams noiseParams) {
        ParameterValidator.validateHeightmap(resolution, initialHeightmap);
        if (noiseParams != null) {
            ParameterValidator.validate(noiseParams);
        }
        String id = store.newId();
        FlowField flowField = FlowField.empty(resolution);
        EvolutionFrame frame0 = new EvolutionFrame(
                0,
                initialHeightmap.clone(),
                flowField.snapshot(),
                new double[resolution * resolution],
                0L,
                null,
                null,
                null);
        return store.start(id, resolution, noiseParams, flowField, frame0);
    }

    /**
     * 在链尾当前状态上再冲刷一轮。前端只给侵蚀参数，不回传、也不需要回传高度数据。
     *
     * @return 追加进去的新一帧（链尾）
     */
    public EvolutionFrame advance(String chainId, ErosionParams params) {
        ParameterValidator.validate(params);
        EvolutionChain chain = requireChain(chainId);

        synchronized (chain) {
            EvolutionFrame current = chain.frame(chain.headIndex());
            FlowAccumulator accumulator = new FlowAccumulator(chain.resolution());
            ErosionResult result = simulator.erode(
                    current.heightmap(), chain.resolution(), params, accumulator);

            long roundSteps = accumulator.totalVisits();
            double[] roundFlow = accumulator.roundFlow();
            double[] cumulativeFlow = chain.flowField().addRound(roundFlow, roundSteps);
            double change = ConvergenceDetector.changeMagnitude(
                    cumulativeFlow, current.flowAccumulation());

            EvolutionFrame next = new EvolutionFrame(
                    current.index() + 1,
                    result.heightmap(),
                    cumulativeFlow,
                    roundFlow,
                    chain.flowField().totalSteps(),
                    params,
                    result.stats(),
                    change);
            store.appendFrame(chain, next);
            return next;
        }
    }

    /**
     * 从链上任意历史帧岔出一条新分支。新链第 0 帧是该帧地形与汇流状态的独立拷贝，
     * 原链不做任何写入。
     */
    public EvolutionChain branch(String chainId, int frameIndex) {
        EvolutionChain parent = requireChain(chainId);
        requireFrame(parent, frameIndex);

        EvolutionFrame source = parent.frame(frameIndex);
        // 分支的活汇流状态从来源帧的累积场快照重建：从同一时刻继续平均，独立累加
        FlowField branchedField = FlowField.from(source.flowAccumulation(), source.cumulativeSteps());
        String newId = store.newId();
        EvolutionFrame frame0 = new EvolutionFrame(
                0,
                source.heightmap().clone(),
                source.flowAccumulation().clone(),
                source.roundFlow().clone(),
                source.cumulativeSteps(),
                source.erosion(),
                source.stats(),
                null);
        return store.branch(newId, parent, frameIndex, branchedField, frame0);
    }

    /** 取某条链的某一帧（不存在的链/帧在计算前被挡下）。 */
    public EvolutionFrame getFrame(String chainId, int frameIndex) {
        EvolutionChain chain = requireChain(chainId);
        requireFrame(chain, frameIndex);
        return chain.frame(frameIndex);
    }

    /** 取链尾帧。 */
    public EvolutionFrame getHead(String chainId) {
        EvolutionChain chain = requireChain(chainId);
        return chain.frame(chain.headIndex());
    }

    /** 列出当前进程内全部演进链（按创建时间升序）。 */
    public List<EvolutionChain> listChains() {
        return store.list();
    }

    /** 取链，不存在则抛 404 语义的异常。 */
    public EvolutionChain requireChain(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            throw new EvolutionNotFoundException("缺少演进链标识 chainId");
        }
        return store.find(chainId)
                .orElseThrow(() -> new EvolutionNotFoundException("演进链不存在: " + chainId));
    }

    /** 判定帧序号是否在链范围内，越界按"资源不存在"处理（404 语义）。 */
    public void requireFrame(EvolutionChain chain, int frameIndex) {
        if (frameIndex < 0 || frameIndex > chain.headIndex()) {
            throw new EvolutionNotFoundException(
                    "演进链 " + chain.id() + " 上不存在帧 #" + frameIndex
                            + "（当前帧范围 0.." + chain.headIndex() + "）");
        }
    }

    /** 链尾累积汇流场相对上一帧的变化幅度；起始帧（没有上一帧可比）为 null。 */
    public Double headFlowChange(EvolutionChain chain) {
        return chain.frame(chain.headIndex()).flowChange();
    }

    /** 依据链尾变化幅度判定河网是否已到稳态（起始帧不算稳态）。 */
    public boolean isConverged(EvolutionChain chain) {
        Double change = headFlowChange(chain);
        return change != null && ConvergenceDetector.isConverged(change);
    }

    public double convergenceThreshold() {
        return ConvergenceDetector.DEFAULT_THRESHOLD;
    }
}
