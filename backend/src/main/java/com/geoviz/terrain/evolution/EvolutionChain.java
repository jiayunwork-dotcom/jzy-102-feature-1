package com.geoviz.terrain.evolution;

import com.geoviz.terrain.noise.NoiseParams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条侵蚀演进链：服务端掌管的有序帧序列，以及它的分支血缘。
 *
 * 链只在末尾追加帧；每帧的地形是下一帧侵蚀的输入，前端推进时无需回传
 * 任何高度数据。岔出的新链是独立的另一条链，只记录「从哪条链的第几帧
 * 岔出」，与父链之间此后没有任何共享的可变状态。
 *
 * 所有对帧列表的读写都在链自身的监视器上同步：同一条链上的侵蚀轮次
 * 必须严格串行（一轮的输入是上一轮的输出），并保证并发请求下
 * 帧序号与「取最后一帧→追加」的复合操作不被拆开。
 */
public final class EvolutionChain {

    private final String id;
    private final int resolution;
    private final NoiseParams noise;
    private final String parentChainId;      // 非根链：岔出它的父链 id；根链为 null
    private final Integer parentFrameIndex;  // 非根链：父链上被作为起点的帧序号
    private final Instant createdAt;
    private final List<EvolutionFrame> frames = new ArrayList<>();

    /**
     * 本链自己逐轮累计起来的汇流场（各轮逐轮汇流场之和），即对河网的
     * 运行时最佳估计。链上河网是否稳态，由这张累计场的相邻帧变化决定：
     * 每多跑一轮，累计场只被稀释约 1/n，故其归一化变化天然随轮次收窄。
     * 随帧本体分开存储——每帧的 {@code flow} 仍是该轮的逐轮场（守恒）。
     */
    private double[] cumulativeFlow;

    EvolutionChain(String id, int resolution, NoiseParams noise,
                   String parentChainId, Integer parentFrameIndex,
                   EvolutionFrame initialFrame) {
        this.id = id;
        this.resolution = resolution;
        this.noise = noise;
        this.parentChainId = parentChainId;
        this.parentFrameIndex = parentFrameIndex;
        this.createdAt = Instant.now();
        this.frames.add(initialFrame);
        this.cumulativeFlow = new double[resolution * resolution];
    }

    public String id() {
        return id;
    }

    public int resolution() {
        return resolution;
    }

    public NoiseParams noise() {
        return noise;
    }

    public String parentChainId() {
        return parentChainId;
    }

    public Integer parentFrameIndex() {
        return parentFrameIndex;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** 是否为从其它链某帧岔出的分支（而非以原始高度场开启的根链）。 */
    public boolean isBranch() {
        return parentChainId != null;
    }

    public synchronized EvolutionFrame lastFrame() {
        return frames.get(frames.size() - 1);
    }

    /** 取指定序号的帧；越界（含负数）抛 {@link EvolutionNotFoundException}。 */
    public synchronized EvolutionFrame frame(int index) {
        if (index < 0 || index >= frames.size()) {
            throw new EvolutionNotFoundException(
                    "演进链 " + id + " 上不存在第 " + index + " 帧，当前共 " + frames.size() + " 帧");
        }
        return frames.get(index);
    }

    /**
     * 提交新一轮的结果：把逐轮汇流场并入累计场，并在链自身的监视器上
     * 原子地追加帧。nextCumulative 由 {@code EvolutionService} 在同一把
     * 锁内算好后传入，保证「取累计场→算变化→追加」不被拆开。
     * （包内可见：只有同包的演进服务负责追加帧。）
     */
    synchronized void commitFrame(EvolutionFrame frame, double[] nextCumulative) {
        this.cumulativeFlow = nextCumulative;
        frames.add(frame);
    }

    /** 当前累计汇流场（每次追加都整体换新数组，取到的旧引用不会被改写）。 */
    synchronized double[] cumulativeFlow() {
        return cumulativeFlow;
    }

    public synchronized int frameCount() {
        return frames.size();
    }

    /** 全部帧的不可变快照（帧本身不可变，列表为拷贝）。 */
    public synchronized List<EvolutionFrame> frames() {
        return List.copyOf(frames);
    }
}
