package com.geoviz.terrain.api;

import com.geoviz.terrain.erosion.ErosionParams;
import com.geoviz.terrain.erosion.ErosionStats;
import com.geoviz.terrain.evolution.EvolutionChain;
import com.geoviz.terrain.evolution.EvolutionFrame;
import com.geoviz.terrain.evolution.EvolutionService;
import com.geoviz.terrain.noise.NoiseParams;
import com.geoviz.terrain.snapshot.Snapshot;
import com.geoviz.terrain.snapshot.SnapshotMeta;
import com.geoviz.terrain.steady.SteadyState;
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
 * 侵蚀演进接口：由服务端掌管的可前进、可回退、可分支的地形时间线。
 *
 * 前端推进时只提交链标识与本轮侵蚀参数，高度场始终留在服务端；
 * 不存在的链/帧以 404 + 原因拒绝，非法侵蚀参数沿用原有校验以 400 + 原因拒绝，
 * 都在任何侵蚀计算开始之前完成。
 */
@RestController
@RequestMapping("/api/evolution")
public class EvolutionController {

    private final EvolutionService evolutionService;

    public EvolutionController(EvolutionService evolutionService) {
        this.evolutionService = evolutionService;
    }

    /** 以一张起始高度场开启一段演进，返回可长期引用的链标识。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChainSummaryDto start(@RequestBody StartRequest request) {
        EvolutionChain chain = evolutionService.start(
                request.resolution(), request.heightmap(), request.noise());
        return toSummary(chain);
    }

    /** 列出全部演进链（含分支血缘与当前稳态），按创建时间升序。 */
    @GetMapping
    public List<ChainSummaryDto> list() {
        return evolutionService.list().stream().map(this::toSummary).toList();
    }

    /** 查一条链：走到了第几帧、每帧当时的参数与汇流场变化幅度、分支血缘。 */
    @GetMapping("/{chainId}")
    public ChainDetailDto getChain(@PathVariable String chainId) {
        EvolutionChain chain = evolutionService.getChain(chainId);
        List<FrameMetaDto> frames = chain.frames().stream()
                .map(f -> new FrameMetaDto(f.index(), f.params(), f.stats(), f.flowChange()))
                .toList();
        SteadyState steady = evolutionService.steadyStatus(chainId);
        return new ChainDetailDto(
                chain.id(), chain.resolution(), chain.frameCount(),
                chain.parentChainId(), chain.parentFrameIndex(), chain.createdAt(),
                steady.steady(), steady.change(), steady.threshold(), frames);
    }

    /** 再推进一轮：只提交参数，后端在自己保存的当前帧上接着算并追加新帧。 */
    @PostMapping("/{chainId}/advance")
    public AdvanceResponseDto advance(@PathVariable String chainId,
                                      @RequestBody AdvanceRequest request) {
        EvolutionService.AdvanceResult result = evolutionService.advance(chainId, request.erosion());
        EvolutionFrame frame = result.frame();
        return new AdvanceResponseDto(
                frame.index(), frame.heightmap(), frame.flow(),
                frame.params(), frame.stats(), frame.flowChange(),
                result.chain().frameCount(),
                result.steady().steady(), result.steady().threshold());
    }

    /** 单独取回链上的一帧（地形 + 汇流场 + 参数 + 统计），用于回看历史帧。 */
    @GetMapping("/{chainId}/frames/{frameIndex}")
    public FrameDto getFrame(@PathVariable String chainId, @PathVariable int frameIndex) {
        EvolutionFrame frame = evolutionService.getFrame(chainId, frameIndex);
        return new FrameDto(frame.index(), frame.heightmap(), frame.flow(),
                frame.params(), frame.stats(), frame.flowChange());
    }

    /** 从链上任意历史帧岔出一条独立新分支，新分支以该帧地形为自己的第 0 帧。 */
    @PostMapping("/{chainId}/branch")
    @ResponseStatus(HttpStatus.CREATED)
    public BranchResponseDto branch(@PathVariable String chainId,
                                    @RequestBody BranchRequest request) {
        EvolutionChain branch = evolutionService.branch(chainId, request.frameIndex());
        EvolutionFrame initialFrame = branch.frame(0);
        return new BranchResponseDto(toSummary(branch),
                new FrameDto(initialFrame.index(), initialFrame.heightmap(), initialFrame.flow(),
                        initialFrame.params(), initialFrame.stats(), initialFrame.flowChange()));
    }

    /** 该链是否已收敛到河网稳态：结论、当前变化幅度与阈值一并告知。 */
    @GetMapping("/{chainId}/steady")
    public SteadyStatusDto steady(@PathVariable String chainId) {
        SteadyState state = evolutionService.steadyStatus(chainId);
        return new SteadyStatusDto(chainId, evolutionService.getChain(chainId).frameCount(),
                state.steady(), state.change(), state.threshold());
    }

    /** 把链上任意一帧导出为命名快照，单独留档。 */
    @PostMapping("/{chainId}/frames/{frameIndex}/snapshot")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotMeta exportFrame(@PathVariable String chainId, @PathVariable int frameIndex,
                                    @RequestBody ExportFrameRequest request) {
        Snapshot snapshot = evolutionService.exportFrame(chainId, frameIndex, request.name());
        return new SnapshotMeta(snapshot.name(), snapshot.savedAt(), snapshot.resolution());
    }

    private ChainSummaryDto toSummary(EvolutionChain chain) {
        SteadyState steady = evolutionService.steadyStatus(chain.id());
        return new ChainSummaryDto(
                chain.id(), chain.resolution(), chain.frameCount(),
                chain.parentChainId(), chain.parentFrameIndex(), chain.createdAt(),
                steady.steady(), steady.change());
    }

    // ---- DTO ----

    public record StartRequest(int resolution, double[] heightmap, NoiseParams noise) {
    }

    public record AdvanceRequest(ErosionParams erosion) {
    }

    public record BranchRequest(int frameIndex) {
    }

    public record ExportFrameRequest(String name) {
    }

    public record ChainSummaryDto(
            String chainId,
            int resolution,
            int frameCount,
            String branchedFromChainId,
            Integer branchedFromFrameIndex,
            Instant createdAt,
            boolean steady,
            Double lastChange
    ) {
    }

    public record FrameMetaDto(int index, ErosionParams params, ErosionStats stats,
                               Double flowChange) {
    }

    public record ChainDetailDto(
            String chainId,
            int resolution,
            int frameCount,
            String branchedFromChainId,
            Integer branchedFromFrameIndex,
            Instant createdAt,
            boolean steady,
            Double lastChange,
            double threshold,
            List<FrameMetaDto> frames
    ) {
    }

    public record FrameDto(
            int index,
            double[] heightmap,
            double[] flow,
            ErosionParams params,
            ErosionStats stats,
            Double flowChange
    ) {
    }

    public record AdvanceResponseDto(
            int index,
            double[] heightmap,
            double[] flow,
            ErosionParams params,
            ErosionStats stats,
            Double flowChange,
            int frameCount,
            boolean steady,
            double threshold
    ) {
    }

    public record BranchResponseDto(ChainSummaryDto chain, FrameDto frame) {
    }

    public record SteadyStatusDto(String chainId, int frameCount, boolean steady,
                                  Double change, double threshold) {
    }
}
