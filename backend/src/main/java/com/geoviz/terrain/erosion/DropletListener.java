package com.geoviz.terrain.erosion;

/**
 * 雨滴生命周期监听器。
 *
 * 模拟器在雨滴的每个关键节点回调该接口，测试借此直接验证
 * "水量单调不升""水量耗尽立即终止"等不变量，而不必反推高度场。
 */
public interface DropletListener {

    DropletListener NONE = new DropletListener() {
    };

    /**
     * 每一步开始时（即即将进行侵蚀/沉积计算之前）回调。
     *
     * @param dropletIndex 当前雨滴序号
     * @param step         当前步数（从 0 开始）
     * @param water        该步开始时的水量（保证 >= minWater，否则该步不会存在）
     * @param sediment     该步开始时携带的泥沙量
     */
    default void onStep(int dropletIndex, int step, double water, double sediment) {
    }

    /**
     * 雨滴每完成一次实际的地表移动（一步）时回调，标明这一步它正处在哪个格点上。
     *
     * <p>回调时机：该步的侵蚀/沉积计算所在的双线性锚点格 (cellX, cellY)，
     * 即 {@link #onStep} 对应的同一步。一次回调 = 雨滴在这个格子上
     * 「流经一次」，汇流场据此给该格点累加一次流经量。
     *
     * <p>保证：一轮模拟中本方法的回调总次数恰好等于 {@code ErosionStats.totalSteps()}，
     * 因为滑出地图的那次尝试不会成为实际一步（没有回调、也不计步）。
     *
     * @param cellX 当前所在格点 x（[0, resolution-1]）
     * @param cellY 当前所在格点 y（[0, resolution-1]）
     */
    default void onTraverse(int dropletIndex, int step, int cellX, int cellY) {
    }

    /**
     * 雨滴消亡时回调。
     *
     * @param reason evaporated（水量蒸发到阈值以下）/ steps_exhausted（步数耗尽）/ out_of_bounds（滑出地图）
     */
    default void onDropletEnd(int dropletIndex, int steps, double finalWater, String reason) {
    }
}
