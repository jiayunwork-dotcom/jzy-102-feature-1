package com.geoviz.terrain.steady;

/**
 * 河网稳态判定模块。
 *
 * 判据只有一条，且与报告值严格自洽：最新相邻两帧之间汇流场的整体变化幅度
 * （见 {@code FlowMetrics.relativeChange}）不超过阈值，即判定该链已收敛到
 * 河网稳态。因此绝不可能出现「变化幅度仍在阈值之上却报告稳态」的矛盾结论。
 */
public class SteadyStateDetector {

    private final double threshold;

    public SteadyStateDetector(double threshold) {
        if (!(threshold > 0.0 && threshold < 1.0)) {
            throw new IllegalArgumentException("稳态阈值必须在 (0, 1) 开区间内，当前值: " + threshold);
        }
        this.threshold = threshold;
    }

    /**
     * 依据最新相邻两帧的汇流场变化幅度给出稳态结论。
     *
     * @param lastFlowChange 最新一帧相对前一帧的汇流场变化幅度；链上还不到两帧时为 null
     */
    public SteadyState assess(Double lastFlowChange) {
        boolean steady = lastFlowChange != null && lastFlowChange <= threshold;
        return new SteadyState(steady, lastFlowChange, threshold);
    }

    public double threshold() {
        return threshold;
    }
}
