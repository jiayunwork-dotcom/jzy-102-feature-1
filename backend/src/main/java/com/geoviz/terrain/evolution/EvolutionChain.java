package com.geoviz.terrain.evolution;

import com.geoviz.terrain.flow.FlowField;
import com.geoviz.terrain.noise.NoiseParams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条侵蚀演进链：服务端自己记着的、有先后顺序的帧序列。
 *
 * <p>链的起点是一帧起始地形（{@code frames.get(0)}），之后只能在末尾逐轮追加，
 * 任何历史帧都不会被原地修改。从某历史帧岔出的分支是一条<strong>新的</strong>
 * {@code EvolutionChain}：它复制那一帧作为自己的第 0 帧，并通过
 * {@code parentChainId} / {@code parentFrameIndex} 记录血缘；原链此后的任何
 * 追加都与它无关，反之亦然——帧的不可变性保证两条链互不污染。
 *
 * <p>本对象的所有变更（追加帧）都在 {@link EvolutionStore} 持有的对象监视器内
 * 完成；读取方法返回的列表/数组也都在锁内做防御性复制，链外无法篡改链内状态。
 *
 * @param id               链的稳定标识（开启演进时由服务端分配）
 * @param resolution       地形网格边长，链内所有帧一致
 * @param noiseParams      起始地形的噪声参数（可为 null：允许从外部提交的高度场开启）
 * @param createdAt        创建时刻
 * @param parentChainId    若本链是分支，记录它从哪条链岔出；根链为 null
 * @param parentFrameIndex 若本链是分支，记录从父链的第几帧岔出；根链为 null
 */
public final class EvolutionChain {

    private final String id;
    private final int resolution;
    private final NoiseParams noiseParams;
    private final Instant createdAt;
    private final String parentChainId;
    private final Integer parentFrameIndex;
    private final List<EvolutionFrame> frames = new ArrayList<>();

    /**
     * 链的「活」汇流状态：持续累积到链尾的 FlowField。帧里存的是每帧的不可变快照，
     * 这里保留可变的累积器供下一轮继续累加；分支时从来源帧的快照重建，互不共享。
     */
    private FlowField flowField;

    public EvolutionChain(String id, int resolution, NoiseParams noiseParams, Instant createdAt,
                          String parentChainId, Integer parentFrameIndex, FlowField flowField) {
        this.id = id;
        this.resolution = resolution;
        this.noiseParams = noiseParams;
        this.createdAt = createdAt;
        this.parentChainId = parentChainId;
        this.parentFrameIndex = parentFrameIndex;
        this.flowField = flowField;
    }

    /** 链的活汇流状态（仅演进服务在链级同步块内访问/更新）。 */
    FlowField flowField() {
        return flowField;
    }

       void append(EvolutionFrame frame) {
        synchronized (frames) {
            frames.add(frame);
        }
    }

    public String id() {
        return id;
    }

    public int resolution() {
        return resolution;
    }

    public NoiseParams noiseParams() {
        return noiseParams;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String parentChainId() {
        return parentChainId;
    }

    public Integer parentFrameIndex() {
        return parentFrameIndex;
    }

    public boolean isBranch() {
        return parentChainId != null;
    }

    public int frameCount() {
        synchronized (frames) {
            return frames.size();
        }
    }

    /** 当前（链尾）帧序号 = 帧数 - 1。 */
    public int headIndex() {
        return frameCount() - 1;
    }

    EvolutionFrame frameInternal(int index) {
        return frames.get(index);
    }

    /** 按序号取帧（越界由调用方/Store 负责拦截）。 */
    public EvolutionFrame frame(int index) {
        synchronized (frames) {
            return frames.get(index);
        }
    }

    /** 全部帧的不可变快照（供链查询列出每帧元信息）。 */
    public List<EvolutionFrame> allFrames() {
        synchronized (frames) {
            return List.copyOf(frames);
        }
    }
}
