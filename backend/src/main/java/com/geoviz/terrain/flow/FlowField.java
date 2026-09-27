package com.geoviz.terrain.flow;

/**
 * 演进过程中持续维护的<strong>累积汇流场</strong>。
 *
 * <p>它记录的是「演进开始以来，顺着地表一路汇下来、流经每个格点的平均水量分布」：
 * 每推进一轮，就把这一轮雨滴逐格点的流经次数（{@link FlowAccumulator}）累加进来，
 * 再按累计总步数归一化。于是：
 * <ul>
 *   <li>早期轮次水系在剧烈改道，每加一轮都会显著改写这张场；</li>
 *   <li>随着一轮轮冲刷，地形与其水系趋于稳定，新一轮的雨只是在已经成型的
 *       河网上重复流淌，对这张平均场的改动越来越小——这正是河网稳态
 *       （{@link ConvergenceDetector}）能够被度量的原因。</li>
 * </ul>
 *
 * <p>归一化后全场总和恒为 1（每个值是一个「流经密度」比例），不同轮次、
 * 不同雨滴数量之间可以直接比较，前端也可直接据此做颜色映射。
 *
 * <p>本对象只维护累积场，逐轮守恒量（本轮流经次数 = 本轮实际步数）由
 * {@link FlowAccumulator} 单独保证。演进帧同时保存两者：累积场用于展示与稳态判定，
 * 单轮流经场用于核对守恒。
 *
 * <p>非线程安全：一次推进在演进服务的链级同步块内完成。
 */
public final class FlowField {

    private final int resolution;
    private final double[] cumulative;
    private long totalSteps;

    private FlowField(int resolution, double[] cumulative, long totalSteps) {
        this.resolution = resolution;
        this.cumulative = cumulative;
        this.totalSteps = totalSteps;
    }

    /** 演进起始帧：还没有任何一轮冲刷，汇流场为全零。 */
    public static FlowField empty(int resolution) {
        if (resolution <= 0) {
            throw new IllegalArgumentException("resolution 必须为正，当前: " + resolution);
        }
        return new FlowField(resolution, new double[resolution * resolution], 0L);
    }

    /**
     * 从某历史帧岔出分支时，沿用该帧当时的累积场继续维护
     * （分支的汇流历史与地形一起继承）。数组做独立拷贝，与父链互不影响。
     */
    public static FlowField from(double[] normalized, long totalSteps) {
        if (normalized == null || normalized.length == 0) {
            throw new IllegalArgumentException("汇流场不能为空");
        }
        int resolution = (int) Math.round(Math.sqrt(normalized.length));
        if ((long) resolution * resolution != normalized.length) {
            throw new IllegalArgumentException("汇流场长度不是平方数: " + normalized.length);
        }
        double[] copy = normalized.clone();
        return new FlowField(resolution, copy, totalSteps);
    }

    /**
     * 追加一轮的流经数据，返回累积汇流场的归一化数组（独立新数组，可安全存入帧）。
     *
     * @param roundFlow 该轮逐格点流经次数（长度与分辨率一致，不被修改）
     * @param roundSteps 该轮全部雨滴实际步数之和（应等于 Σ roundFlow）
     */
    public double[] addRound(double[] roundFlow, long roundSteps) {
        if (roundFlow.length != cumulative.length) {
            throw new IllegalArgumentException(
                    "本轮流经场长度 " + roundFlow.length + " 与汇流场 " + cumulative.length + " 不一致");
        }
        if (roundSteps <= 0) {
            // 本轮没有任何实际步：累积场维持不变
            return snapshot();
        }
        totalSteps += roundSteps;
        for (int i = 0; i < cumulative.length; i++) {
            cumulative[i] += roundFlow[i];
        }
        return snapshot();
    }

    /** 当前累积场的归一化快照（新数组）；尚无任何步数时为全零。 */
    public double[] snapshot() {
        double[] out = new double[cumulative.length];
        if (totalSteps > 0) {
            for (int i = 0; i < cumulative.length; i++) {
                out[i] = cumulative[i] / totalSteps;
            }
        }
        return out;
    }

    public int resolution() {
        return resolution;
    }

    /** 截至目前累计的全部雨滴实际步数。 */
    public long totalSteps() {
        return totalSteps;
    }
}
