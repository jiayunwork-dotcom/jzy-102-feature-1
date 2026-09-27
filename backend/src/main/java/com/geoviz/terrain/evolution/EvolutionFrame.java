package com.geoviz.terrain.evolution;

import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionStats;

import java.time.Instant;

/**
 * 演进链上的一帧：某一时刻的地形及其水系快照。
 *
 * 帧一旦产生即不可变（高度场与汇流场数组此后绝不会被任何代码改写）：
 * 侵蚀模拟器以输入场的副本做计算，追加帧只新建数组，因此历史帧可以
 * 稳定回看，也不会被同链后续轮次或别的分支污染。
 *
 * @param index      帧在所属链内的序号，从 0 开始
 * @param heightmap  该帧的高度场（行优先，长度 resolution^2）
 * @param flow       产生这一帧那一轮雨滴的逐轮汇流场（总和恰等于该轮总步数）；
 *                   起始帧（index=0，尚未冲刷）为零场
 * @param params     产生这一帧所用的侵蚀参数；起始帧为 null
 * @param stats      产生这一帧那一轮的雨滴/步数/总高度统计；起始帧为 null
 * @param flowChange 链的累计汇流场在本帧相对前一帧的整体变化幅度（∈ [0, 1]），
 *                   随轮次约 1/n 收窄，作为河网稳态判据；起始帧为 null
 * @param createdAt  产生时间
 */
public record EvolutionFrame(
        int index,
        double[] heightmap,
        double[] flow,
        ErosionParams params,
        ErosionStats stats,
        Double flowChange,
        Instant createdAt
) {
}
