package com.geoviz.terrain.flow;

import com.geoviz.terrain.erosion.DropletListener;

/**
 * 单轮汇流累加器：在一轮雨滴侵蚀模拟中，逐格点累计「流经量」。
 *
 * <p>语义：雨滴每完成一次实际移动（一步），它当时所在的格点就被流经一次，
 * 累加器给该格点 +1。雨滴受坡度与惯性牵引沿谷地行走，被经过越多的格点
 * （上游集水范围越大）数值越高，于是场上呈现河网般的高值脉络。
 *
 * <p>守恒关系（由 {@link DropletListener#onTraverse} 的调用时机保证）：
 * <pre>
 *   本轮 onTraverse 回调次数 = 本轮全部雨滴实际步数之和 = Σ_i roundFlow[i]
 * </pre>
 * 一次回调都不会多（滑出地图的尝试不是实际一步，不回调）也不会少
 * （每一步都恰好回调一次）。
 *
 * <p>本累加器只记录<strong>单轮</strong>的流经情况，是一次性的逐轮工具，
 * 由演进服务每轮新建；跨轮的持续汇流场由 {@link FlowField} 维护。
 * 非线程安全（单轮侵蚀本身单线程串行模拟）。
 */
public final class FlowAccumulator implements DropletListener {

    private final int resolution;
    private final double[] roundFlow;
    private long totalVisits;

    public FlowAccumulator(int resolution) {
        if (resolution <= 0) {
            throw new IllegalArgumentException("resolution 必须为正，当前: " + resolution);
        }
        this.resolution = resolution;
        this.roundFlow = new double[resolution * resolution];
    }

    @Override
    public void onTraverse(int dropletIndex, int step, int cellX, int cellY) {
        roundFlow[cellY * resolution + cellX] += 1.0;
        totalVisits++;
    }

    /** 本轮逐格点流经次数（行优先，长度 resolution^2）。返回内部数组，调用方不应再修改。 */
    public double[] roundFlow() {
        return roundFlow;
    }

    /** 本轮全部雨滴实际步数之和，恒等于 {@code Σ_i roundFlow()[i]}。 */
    public long totalVisits() {
        return totalVisits;
    }
}
