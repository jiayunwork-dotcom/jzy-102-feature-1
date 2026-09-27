package com.geoviz.terrain.flow;

/**
 * 汇流场的整体比较：把两场各自归一化为概率分布后求总变差距离（∈ [0, 1]），
 * 作为「汇流场整体挪动了多少」的度量。
 *
 * 演进链的稳态判据把它作用在链的<b>累计汇流场</b>上（各轮逐轮汇流场之和）：
 * 每多跑一轮，累计场只被新一轮稀释约 1/n，故相邻两帧的累计场变化随轮次
 * 单调收窄并趋于 0——河网的运行时估计稳定了下来。直接比较逐轮场会被
 * 雨滴微观路径的逐轮抖动主导（受驱耗散系统的固有涨落），无法体现
 * 「河网」层面的稳定，因此不作为判据。
 *
 * 归一化让度量与雨滴数量、总步数无关（不同参数下阈值仍有意义）。
 */
public final class FlowMetrics {

    private FlowMetrics() {
    }

    /**
     * 两场汇流场的整体变化幅度：0.5 * Σ|p_i - q_i|，其中 p、q 为两场
     * 各自除以总和后的归一化分布（总变差距离，∈ [0, 1]）。
     *
     * 边界约定：两场皆为零场（链尚未跑过任何一轮）→ 0；恰好一场为零场 → 1（最大差异）。
     */
    public static double relativeChange(double[] previous, double[] next) {
        if (previous == null || next == null || previous.length != next.length) {
            throw new IllegalArgumentException("两场汇流场必须同尺寸且非空");
        }
        double sumPrev = 0;
        double sumNext = 0;
        for (int i = 0; i < previous.length; i++) {
            sumPrev += previous[i];
            sumNext += next[i];
        }
        if (sumPrev == 0 && sumNext == 0) {
            return 0.0;
        }
        if (sumPrev == 0 || sumNext == 0) {
            return 1.0;
        }
        double diff = 0;
        for (int i = 0; i < previous.length; i++) {
            diff += Math.abs(previous[i] / sumPrev - next[i] / sumNext);
        }
        return 0.5 * diff;
    }
}
