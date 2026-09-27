package com.geoviz.terrain.steady;

/**
 * 一条演进链当前的稳态结论。
 *
 * @param steady    是否已收敛到河网稳态
 * @param change    最新相邻两帧之间汇流场的整体变化幅度（∈ [0, 1]）；
 *                  链上还不到两帧（未跑过任何一轮）时为 null
 * @param threshold 判定所用的阈值：change ≤ threshold 即视为稳态
 */
public record SteadyState(boolean steady, Double change, double threshold) {
}
