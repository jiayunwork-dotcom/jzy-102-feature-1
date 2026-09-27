package com.geoviz.terrain.api;

import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionStats;
import com.geoviz.terrain.evolution.EvolutionChain;
import com.geoviz.terrain.evolution.EvolutionFrame;
import com.geoviz.terrain.evolution.EvolutionService;
import com.geoviz.terrain.flow.ConvergenceDetector;
import com.geoviz.terrain.noise.NoiseParams;
import com.geoviz.terrain.snapshot.Snapshot;
import com.geoviz.terrain.snapshot.SnapshotStore;
import com.geoviz.terrain.validation.ParameterValidator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 侵蚀演进接口：由服务端掌管演进状态的时间线/分支能力。
 *
 * <ul>
 *   <li>POST /api/evolutions                 用起始高度场开启一段演进；</li>
 *   <li>GET  /api/evolutions                 列出全部链及其分支血缘；</li>
 *   <li>GET  /api/evolutions/{id}            查一条链：走到第几帧、每帧参数、稳态；</li>
 *   <li>POST /api/evolutions/{id}/advance    在服务端保存的当前状态上再冲刷一轮；</li>
 *   <li>POST /api/evolutions/{id}/branch     从任意历史帧岔出新分支；</li>
 *   <li>GET  /api/evolutions/{id}/frames/{n} 单独取回某一帧（地形 + 汇流场）。</li>
 * </ul>
 * 推进接口只接收侵蚀参数，不接收也不允许依赖回传的高度数据。
 */
@RestController
@RequestMapping("/api/evolutions")
public class EvolutionController {

    private final EvolutionService service;
    private final SnapshotStore snapshots;

    public EvolutionController(EvolutionService service, SnapshotStore snapshots) {
        this.service = service;
        this.snapshots = snapshots;
    }

    /** 用一张起始高度场开启演进，返回链信息（含第 0 帧）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChainInfo start(@RequestBody StartRequest request) {
        ParameterValidator.validateHeightmap(request.resolution(), request.heightmap());
        if (request.noise() != null) {
            ParameterValidator.validate(request.noise());
        }
        EvolutionChain chain = service.start(request.resolution(), request.heightmap(), request.noise());
        return ChainInfo.of(chain, service, service.getFrame(chain.id(), 0));
    }

    /** 列出全部演进链的元信息（不含高度数据）。 */
    @GetMapping
    public List<ChainInfo> list() {
        return service.listChains().stream()
                .map(c -> ChainInfo.of(c, service, null))
                .toList();
    }

    /** 查一条链：帧数量、每帧元信息、当前变化幅度与稳态结论、分支血缘。 */
    @GetMapping("/{id}")
    public ChainInfo get(@PathVariable String id) {
        EvolutionChain chain = service.requireChain(id);
        return ChainInfo.of(chain, service, service.getHead(id));
    }

    /** 在链尾当前状态上追加一轮侵蚀，返回新帧（并带最新链信息）。 */
    @PostMapping("/{id}/advance")
    public AdvanceResponse advance(@PathVariable String id, @RequestBody AdvanceRequest request) {
        // 参数先于计算校验（缺侵蚀参数/非法都在动土前 400）；链不存在由服务层抛 404
        if (request == null || request.erosion() == null) {
            throw new com.geoviz.terrain.validation.InvalidParameterException("缺少侵蚀参数 erosion");
        }
        ParameterValidator.validate(request.erosion());
        EvolutionChain chain = service.requireChain(id);
        EvolutionFrame frame = service.advance(id, request.erosion());
        return new AdvanceResponse(ChainInfo.of(chain, service, frame), FramePayload.of(frame));
    }

    /** 从指定历史帧岔出一条独立分支。 */
    @PostMapping("/{id}/branch")
    @ResponseStatus(HttpStatus.CREATED)
    public ChainInfo branch(@PathVariable String id, @RequestBody BranchRequest request) {
        if (request == null || request.frameIndex() == null) {
            throw new com.geoviz.terrain.validation.InvalidParameterException("缺少要岔出的帧序号 frameIndex");
        }
        EvolutionChain branch = service.branch(id, request.frameIndex());
        return ChainInfo.of(branch, service, service.getFrame(branch.id(), 0));
    }

    /** 单独取回某一帧的完整数据：地形 + 汇流场 + 参数 + 统计。 */
    @GetMapping("/{id}/frames/{frameIndex}")
    public FramePayload frame(@PathVariable String id, @PathVariable int frameIndex) {
        return FramePayload.of(service.getFrame(id, frameIndex));
    }

    /** 把任意一帧导出成命名快照（既有快照体系），便于单独留档。 */
    @PostMapping("/{id}/frames/{frameIndex}/snapshot")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotMetaDto exportSnapshot(@PathVariable String id,
                                          @PathVariable int frameIndex,
                                          @RequestBody ExportSnapshotRequest request) {
        if (request == null || request.name() == null) {
            throw new com.geoviz.terrain.validation.InvalidParameterException("缺少快照名称 name");
        }
        ParameterValidator.validateSnapshotName(request.name());
        EvolutionChain chain = service.requireChain(id);
        EvolutionFrame frame = service.getFrame(id, frameIndex);
        Snapshot saved = snapshots.save(request.name(), chain.resolution(),
                frame.heightmap(), chain.noiseParams(), frame.erosion());
        return new SnapshotMetaDto(saved.name(), saved.savedAt(), saved.resolution());
    }

    // ---- 请求/响应记录 ----

    public record StartRequest(int resolution, double[] heightmap, NoiseParams noise) {
    }

    public record AdvanceRequest(ErosionParams erosion) {
    }

    public record BranchRequest(Integer frameIndex) {
    }

    public record ExportSnapshotRequest(String name) {
    }

    public record SnapshotMetaDto(String name, Instant savedAt, int resolution) {
    }

    public record FrameMeta(
            int index,
            ErosionParams erosion,
            ErosionStats stats,
            Double flowChange,
            long cumulativeSteps,
            double roundFlowTotal
    ) {
        static FrameMeta of(EvolutionFrame f) {
            double roundTotal = 0;
            for (double v : f.roundFlow()) {
                roundTotal += v;
            }
            return new FrameMeta(f.index(), f.erosion(), f.stats(), f.flowChange(),
                    f.cumulativeSteps(), roundTotal);
        }
    }

    public record FramePayload(
            int index,
            double[] heightmap,
            double[] flowAccumulation,
            double[] roundFlow,
            long cumulativeSteps,
            ErosionParams erosion,
            ErosionStats stats,
            Double flowChange,
            double roundFlowTotal
    ) {
        static FramePayload of(EvolutionFrame f) {
            double roundTotal = 0;
            for (double v : f.roundFlow()) {
                roundTotal += v;
            }
            return new FramePayload(f.index(), f.heightmap(), f.flowAccumulation(), f.roundFlow(),
                    f.cumulativeSteps(), f.erosion(), f.stats(), f.flowChange(), roundTotal);
        }
    }

    public record ChainInfo(
            String id,
            int resolution,
            Instant createdAt,
            int frameCount,
            int headIndex,
            String parentChainId,
            Integer parentFrameIndex,
            boolean branch,
            List<FrameMeta> frames,
            Double currentFlowChange,
            double convergenceThreshold,
            boolean converged
    ) {
        static ChainInfo of(EvolutionChain chain, EvolutionService svc, EvolutionFrame head) {
            List<FrameMeta> metas = chain.allFrames().stream().map(FrameMeta::of).toList();
            Double change = head != null ? head.flowChange() : null;
            boolean converged = change != null && ConvergenceDetector.isConverged(change);
            return new ChainInfo(
                    chain.id(),
                    chain.resolution(),
                    chain.createdAt(),
                    chain.frameCount(),
                    chain.headIndex(),
                    chain.parentChainId(),
                    chain.parentFrameIndex(),
                    chain.isBranch(),
                    metas,
                    change,
                    svc.convergenceThreshold(),
                    converged);
        }
    }

    public record AdvanceResponse(ChainInfo chain, FramePayload head) {
    }
}
