package com.geoviz.terrain.flow;

/**
 * 河网稳态判定模块：比较相邻两帧<strong>累积汇流场</strong>的整体变化幅度，
 * 并给出是否已收敛的结论。
 *
 * <p>变化幅度（flow change）采用逐格点绝对偏差的 L1 总量
 * （两张场都已按累计总步数归一化，全场总和恒为 1）：
 * <pre>
 *   change_t = Σ_i |flow_t[i] - flow_{t-1}[i]|
 * </pre>
 * 它可解释为「截至本轮，水流在格点间的平均分布相对上一帧有多大比例发生了重新分配」：
 * 两帧分布一致时为 0；两张分布完全不相交时为 2。
 *
 * <p><strong>为什么它在参数不变的持续推进下一路收窄（单调不增的依据）</strong>：
 * 累积汇流场是逐轮流经分布 q_t 按总步数加权的平均，
 * <pre>
 *   flow_t = (m_1 q_1 + … + m_t q_t) / M_t，  M_t = Σ m_k
 * </pre>
 * 相邻两帧之差满足
 * <pre>
 *   change_t = (m_t / M_t) · ‖q_t − flow_{t-1}‖_1 ≤ 2 m_t / M_t
 * </pre>
 * 每轮雨滴数量不变时 m_t 基本恒定，系数 m_t/M_t 随轮次严格递减，
 * 因此变化幅度的上界本身一路收窄，实测值也随之单调下降，直至跨过阈值
 * 被判为稳态——不会出现「越推越剧烈却报稳态」的矛盾。
 *
 * <p>纯函数工具，无状态、线程安全；阈值只是本工具的判定约定，
 * 不参与任何模拟计算，不影响可复现性。
 */
public final class ConvergenceDetector {

    /** 稳态阈值：相邻两帧累积汇流场的变化幅度低于或等于该值即认为河网稳定。 */
    public static final double DEFAULT_THRESHOLD = 0.03;

    private ConvergenceDetector() {
    }

    /**
     * 计算两张（已归一化的）累积汇流场之间的整体变化幅度。
     *
     * @param current  当前帧汇流场（全场总和应为 1；起始全零帧也可）
     * @param previous 上一帧汇流场（长度须一致）
     * @return ≥0 的 L1 变化幅度（两张归一化分布下取值不超过 2）
     * @throws IllegalArgumentException 两场长度不一致或为 null
     */
    public static double changeMagnitude(double[] current, double[] previous) {
        if (current == null || previous == null) {
            throw new IllegalArgumentException("汇流场不能为 null");
        }
        if (current.length != previous.length) {
            throw new IllegalArgumentException(
                    "汇流场长度不一致：" + current.length + " vs " + previous.length);
        }
        double diffSum = 0;
        for (int i = 0; i < current.length; i++) {
            diffSum += Math.abs(current[i] - previous[i]);
        }
        return diffSum;
    }

    /** 用默认阈值 {@link #DEFAULT_THRESHOLD} 判定。 */
    public static boolean isConverged(double changeMagnitude) {
        return isConverged(changeMagnitude, DEFAULT_THRESHOLD);
    }

    /** 显式给定阈值判定；变化幅度恰好等于阈值也算稳态。 */
    public static boolean isConverged(double changeMagnitude, double threshold) {
        return changeMagnitude <= threshold;
    }
}
