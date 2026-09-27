package com.geoviz.terrain.flow;

import com.geoviz.terrain.erosion.DropletListener;

/**
 * 汇流量场计算模块。
 *
 * 作为 {@link DropletListener} 挂进一轮侵蚀模拟：每颗雨滴每走一步，
 * 就把它当时所在格点的流经计数加一。雨滴顺坡而下，山谷格点被反复流经，
 * 于是一张与地形同尺寸的「汇流量场」上，河道自然连成高值脉络。
 *
 * 守恒性：每个格点的数值都是整数计数值（被流经的次数），
 * 整张场的总和恰好等于这一轮全部雨滴实际走过的步数之和
 * （与 {@code ErosionStats.totalSteps} 严格相等，不多一步也不少一步），
 * 测试可据此把总量精确对上。
 */
public final class FlowAccumulator implements DropletListener {

    private final int resolution;
    private final double[] flow;
    private long totalSteps;

    public FlowAccumulator(int resolution) {
        this.resolution = resolution;
        this.flow = new double[resolution * resolution];
        this.totalSteps = 0;
    }

    /**
     * 雨滴每执行一步，给它当时所在格点贡献恰好一次流经量。
     * 模拟器保证坐标落在 [0, resolution-1) 内，向下取整即为所在格点。
     */
    @Override
    public void onStepPosition(int dropletIndex, int step, double posX, double posY) {
        int x = (int) posX;
        int y = (int) posY;
        flow[y * resolution + x] += 1.0;
        totalSteps++;
    }

    /** 汇流量场（行优先，长度 resolution^2），每个格点是该轮被流经的次数。 */
    public double[] flowField() {
        return flow;
    }

    /** 本场累计的流经总次数（= 这一轮全部雨滴的总步数）。 */
    public long totalSteps() {
        return totalSteps;
    }
}
