package com.geoviz.terrain.evolution;

import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionStats;

/**
 * 侵蚀演进链上的一帧：链的一次不可变状态快照。
 *
 * <p>第 0 帧是开启演进时的起始地形（{@code erosion == null}、两张汇流场均为全零、
 * {@code stats == null}、{@code flowChange == null}、{@code cumulativeSteps == 0}）；
 * 此后每追加一轮侵蚀生成一个 index 递增的新帧。
 *
 * <p>每张帧保存两张汇流场，各司其职：
 * <ul>
 *   <li>{@code roundFlow}：产生该帧<strong>这一轮</strong>雨滴的逐格点流经次数，
 *       全场之和恰好等于该轮全部雨滴的实际步数之和（{@code stats.totalSteps}），
 *       用于核对守恒；</li>
 *   <li>{@code flowAccumulation}：演进开始以来持续维护、按累计步数归一化的
 *       累积汇流场（全场之和为 1），是河网形态与稳态判定的对象。</li>
 * </ul>
 *
 * <p>帧一旦追加进链就不再被修改，这是分支隔离与逐比特回放的数据基础。
 *
 * @param index            帧在所属链内的序号（从 0 开始）
 * @param heightmap        该帧的地形高度场（行优先，长度 resolution^2）
 * @param flowAccumulation 截至该帧的归一化累积汇流场（河网/稳态用，全场和为 1）
 * @param roundFlow        产生该帧那一轮的逐格点流经次数（守恒核对用；第 0 帧为全零）
 * @param cumulativeSteps  截至该帧累计的全部雨滴实际步数
 * @param erosion          产生该帧所用的侵蚀参数（第 0 帧为 null）
 * @param stats            该轮侵蚀统计（第 0 帧为 null）
 * @param flowChange       该帧累积汇流场相对上一帧的整体变化幅度；
 *                         第 0 帧为 null，第 1 帧为相对全零起始场的变化（=1，只要该轮有步），
 *                         此后随轮次收窄
 */
public record EvolutionFrame(
        int index,
        double[] heightmap,
        double[] flowAccumulation,
        double[] roundFlow,
        long cumulativeSteps,
        ErosionParams erosion,
        ErosionStats stats,
        Double flowChange
) {
}
