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
     * 雨滴成功走完一步后回调其到达的位置（紧跟移动与步进计数之后）。
     *
     * 汇流量场借此把每一步记为对到达格点的一次流经：一颗雨滴实际走了多少步，
     * 这个回调就被触发多少次——滑出地图的那次尝试不构成实际一步、不会触发，
     * 因此整图流经次数之和恰好等于全部雨滴的实际总步数。
     *
     * @param step 刚走完的这一步的序号（从 1 开始）
     * @param posX 这一步到达位置的 x 坐标（保证在 [0, resolution-1) 内）
     * @param posY 这一步到达位置的 y 坐标（保证在 [0, resolution-1) 内）
     */
    default void onStepPosition(int dropletIndex, int step, double posX, double posY) {
    }

    /**
     * 雨滴消亡时回调。
     *
     * @param reason evaporated（水量蒸发到阈值以下）/ steps_exhausted（步数耗尽）/ out_of_bounds（滑出地图）
     */
    default void onDropletEnd(int dropletIndex, int steps, double finalWater, String reason) {
    }
}
