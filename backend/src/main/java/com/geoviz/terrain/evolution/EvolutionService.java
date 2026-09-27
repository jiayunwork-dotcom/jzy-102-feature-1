package com.geoviz.terrain.evolution;

import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionResult;
import com.geoviz.terrain.erosion.ErosionSimulator;
import com.geoviz.terrain.flow.FlowAccumulator;
import com.geoviz.terrain.flow.FlowMetrics;
import com.geoviz.terrain.noise.NoiseParams;
import com.geoviz.terrain.snapshot.Snapshot;
import com.geoviz.terrain.snapshot.SnapshotStore;
import com.geoviz.terrain.steady.SteadyState;
import com.geoviz.terrain.steady.SteadyStateDetector;
import com.geoviz.terrain.validation.ParameterValidator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 演进状态管理模块：由服务端掌管的侵蚀时间线（链）及其分支。
 *
 * 职责：
 * <ul>
 *   <li>以一张起始高度场开启一条演进链，返回可长期引用的链标识；</li>
 *   <li>「再推进一轮」只在链自己保存的当前帧上接着算——前端不回传任何
 *       高度数据——并把这一轮的地形、汇流场、参数、统计、相对上一帧的
 *       汇流场变化幅度作为新帧追加到链尾；</li>
 *   <li>从任意历史帧岔出独立新分支：起点地形做防御性拷贝，之后两条链
 *       各走各的，互不改动；</li>
 *   <li>单帧取回、链信息（帧序、每帧参数、分支血缘）查询、稳态结论、
 *       以及把任意一帧导出成命名快照。</li>
 * </ul>
 *
 * 链为进程内活数据（{@link ConcurrentHashMap}），进程重启即失；
 * 进程存活期间帧不可变、存取稳定。演进本身不引入任何随机源：
 * 每轮的全部随机性仍只来自该轮侵蚀参数里的种子，因此同一条参数序列
 * 从同一初始场重放，逐帧逐比特一致。
 */
@Component
public class EvolutionService {

    private final ConcurrentMap<String, EvolutionChain> chains = new ConcurrentHashMap<>();
    private final ErosionSimulator erosionSimulator;
    private final SteadyStateDetector steadyStateDetector;
    private final SnapshotStore snapshotStore;

    public EvolutionService(ErosionSimulator erosionSimulator,
                            SteadyStateDetector steadyStateDetector,
                            SnapshotStore snapshotStore) {
        this.erosionSimulator = erosionSimulator;
        this.steadyStateDetector = steadyStateDetector;
        this.snapshotStore = snapshotStore;
    }

    /**
     * 以一张起始高度场开启一条根演进链（第 0 帧 = 起始地形，汇流场为零场）。
     * 高度场做防御性拷贝，调用方此后修改数组不影响链上第 0 帧。
     */
    public EvolutionChain start(int resolution, double[] heightmap, NoiseParams noise) {
        ParameterValidator.validateHeightmap(resolution, heightmap);
        if (noise != null) {
            ParameterValidator.validate(noise);
        }
        String id = UUID.randomUUID().toString();
        EvolutionFrame frame0 = new EvolutionFrame(
                0, heightmap.clone(), new double[resolution * resolution],
                null, null, null, Instant.now());
        EvolutionChain chain = new EvolutionChain(id, resolution, noise, null, null, frame0);
        chains.put(id, chain);
        return chain;
    }

    /**
     * 在指定链的当前（最后）帧上推进一轮侵蚀，把结果追加为新的一帧。
     *
     * 侵蚀参数先于一切计算被校验；链不存在则直接抛 {@link EvolutionNotFoundException}。
     * 模拟器在输入场的副本上计算，前一帧的高度数组不会被触碰；
     * 汇流场由挂入模拟过程的 {@link FlowAccumulator} 同步统计。
     *
     * 一帧上同时落两种汇流信息：帧的 {@code flow} 是这一轮的逐轮场
     * （总和恰等于本轮总步数，用于守恒核对与河网叠加）；链另维护一张
     * 逐轮累加的累计汇流场，{@code flowChange} 记累计场相邻两帧的归一化
     * 距离——河网运行时估计的变化，随轮次约 1/n 收窄，作为稳态判据。
     */
    public AdvanceResult advance(String chainId, ErosionParams params) {
        ParameterValidator.validate(params);
        EvolutionChain chain = requireChain(chainId);
        synchronized (chain) {
            EvolutionFrame previous = chain.lastFrame();
            FlowAccumulator flowAccumulator = new FlowAccumulator(chain.resolution());
            ErosionResult result = erosionSimulator.erode(
                    previous.heightmap(), chain.resolution(), params, flowAccumulator);

            double[] perRoundFlow = flowAccumulator.flowField();
            double[] previousCumulative = chain.cumulativeFlow();
            double[] nextCumulative = new double[chain.resolution() * chain.resolution()];
            for (int i = 0; i < nextCumulative.length; i++) {
                nextCumulative[i] = previousCumulative[i] + perRoundFlow[i];
            }
            double change = FlowMetrics.relativeChange(previousCumulative, nextCumulative);

            EvolutionFrame frame = new EvolutionFrame(
                    previous.index() + 1,
                    result.heightmap(),
                    perRoundFlow,
                    params,
                    result.stats(),
                    change,
                    Instant.now());
            chain.commitFrame(frame, nextCumulative);
            return new AdvanceResult(chain, frame, steadyStateDetector.assess(change));
        }
    }

    /**
     * 从父链的指定历史帧岔出一条独立新分支：新链第 0 帧只是该帧地形的
     * 防御性拷贝（第 0 帧的逐轮汇流场为零——它是新链的起点，尚未在本链冲刷），
     * 并记下血缘「从哪条链的第几帧岔出」。新链之后追加的帧只属于它自己，
     * 累计场也从自己的第一轮重新积累，父链任何一帧都不会被改动。
     */
    public EvolutionChain branch(String parentChainId, int frameIndex) {
        EvolutionChain parent = requireChain(parentChainId);
        EvolutionFrame source;
        synchronized (parent) {
            source = parent.frame(frameIndex); // 越界在此被带原因拒绝
        }
        String id = UUID.randomUUID().toString();
        EvolutionFrame initialFrame = new EvolutionFrame(
                0,
                source.heightmap().clone(),
                new double[parent.resolution() * parent.resolution()],
                null,
                null,
                null,
                Instant.now());
        EvolutionChain branch = new EvolutionChain(
                id, parent.resolution(), parent.noise(), parent.id(), frameIndex, initialFrame);
        chains.put(id, branch);
        return branch;
    }

    public EvolutionChain getChain(String chainId) {
        return requireChain(chainId);
    }

    public EvolutionFrame getFrame(String chainId, int frameIndex) {
        EvolutionChain chain = requireChain(chainId);
        return chain.frame(frameIndex);
    }

    /** 链的当前稳态结论：以最后两帧之间汇流场的变化幅度为判据。 */
    public SteadyState steadyStatus(String chainId) {
        EvolutionChain chain = requireChain(chainId);
        EvolutionFrame last = chain.lastFrame();
        return steadyStateDetector.assess(last.flowChange());
    }

    /** 按创建时间升序列出全部演进链。 */
    public List<EvolutionChain> list() {
        return chains.values().stream()
                .sorted(Comparator.comparing(EvolutionChain::createdAt))
                .toList();
    }

    /**
     * 把链上任意一帧导出为命名快照（与原有快照接口共用同一个内存存储），
     * 方便单独留档；不影响演进链本身。
     */
    public Snapshot exportFrame(String chainId, int frameIndex, String snapshotName) {
        ParameterValidator.validateSnapshotName(snapshotName);
        EvolutionChain chain = requireChain(chainId);
        EvolutionFrame frame = chain.frame(frameIndex);
        return snapshotStore.save(snapshotName, chain.resolution(), frame.heightmap(),
                chain.noise(), frame.params());
    }

    private EvolutionChain requireChain(String chainId) {
        EvolutionChain chain = chainId == null ? null : chains.get(chainId);
        if (chain == null) {
            throw new EvolutionNotFoundException("演进链不存在: " + chainId);
        }
        return chain;
    }

    /** 推进一轮的结果：所属链、新追加的帧、以及以新帧为链尾时的稳态结论。 */
    public record AdvanceResult(EvolutionChain chain, EvolutionFrame frame, SteadyState steady) {
    }
}
