package com.geoviz.terrain.evolution;

import com.geoviz.terrain.flow.FlowField;
import com.geoviz.terrain.noise.NoiseParams;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 侵蚀演进状态存储模块：服务端进程内的演进链注册表。
 *
 * <p>只负责「链/帧的存取与结构不变量」，不做任何侵蚀、汇流、收敛计算：
 * <ul>
 *   <li>链由稳定的字符串 id 标识，id 由本模块用 UUID 分配，进程存活期间不变；</li>
 *   <li>帧只能在某条链的末尾追加，追加在演进服务持有的链级监视器内原子完成；</li>
 *   <li>历史帧没有任何修改入口——分支是复制某帧开启一条新链，原链的帧序列
 *       在创建分支后字节级不变；</li>
 *   <li>存的是内存活数据：不持久化，进程重启即清空（与 SnapshotStore 的取舍一致），
 *       但只要进程活着，链、帧、分支关系就稳定可取。</li>
 * </ul>
 *
 * <p>存进帧里的地形/汇流数组在服务层已做防御性拷贝，演进链永远持有自己的一份数据，
 * 调用方后续修改数组不会反向污染链内状态。
 */
@Component
public class EvolutionStore {

    private final ConcurrentMap<String, EvolutionChain> chains = new ConcurrentHashMap<>();

    /** 用一张起始高度场开启一条根演进链，返回新链（已含第 0 帧）。 */
    public EvolutionChain start(String id, int resolution, NoiseParams noiseParams,
                                FlowField flowField, EvolutionFrame initialFrame) {
        EvolutionChain chain = new EvolutionChain(id, resolution, noiseParams, Instant.now(),
                null, null, flowField);
        chain.append(initialFrame);
        chains.put(id, chain);
        return chain;
    }

    /**
     * 从父链的某一帧岔出一条新链：复制该帧作为新链第 0 帧，并从该帧的累积汇流场
     * 重建独立的活汇流状态。父链在此过程中只读不写，新链与父链不共享任何可变数组。
     */
    public EvolutionChain branch(String newId, EvolutionChain parent, int sourceFrameIndex,
                                 FlowField flowField, EvolutionFrame initialFrame) {
        EvolutionChain branch = new EvolutionChain(newId, parent.resolution(), parent.noiseParams(),
                Instant.now(), parent.id(), sourceFrameIndex, flowField);
        branch.append(initialFrame);
        chains.put(newId, branch);
        return branch;
    }

    /** 在某条链末尾原子追加一帧。 */
    public void appendFrame(EvolutionChain chain, EvolutionFrame frame) {
        chain.append(frame);
    }

    public Optional<EvolutionChain> find(String id) {
        return Optional.ofNullable(chains.get(id));
    }

    /** 按创建时间升序列出全部链。 */
    public List<EvolutionChain> list() {
        return chains.values().stream()
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .toList();
    }

    /** 分配一个尚未占用的链标识。 */
    public String newId() {
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (chains.containsKey(id));
        return id;
    }
}
