package com.geoviz.terrain.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 演进接口层测试：开启→推进→查链/查帧→分支→稳态→导出快照的完整往返，
 * 以及不存在链/帧、越界帧序号、非法侵蚀参数在计算前被带原因拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvolutionApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int RES = 32;

    private static String erosionJson(long seed) {
        return """
                {"dropletCount": 500, "erodeRate": 0.3, "depositRate": 0.3,
                 "evaporateRate": 0.02, "gravity": 4.0, "capacityFactor": 1.5,
                 "baseFlow": 0.01, "inertia": 0.05, "maxSteps": 32,
                 "minWater": 0.01, "seed": %d}
                """.formatted(seed);
    }

    /** 先经原有噪声生成接口拿一张真实起伏地形，再用它开启演进。 */
    private String startChainFromGeneratedTerrain() throws Exception {
        String noiseJson = """
                {"resolution": %d, "octaves": 4, "lacunarity": 2.0,
                 "persistence": 0.5, "baseFrequency": 3.0, "seed": 42}
                """.formatted(RES);
        String generated = mvc.perform(post("/api/terrain/generate")
                        .contentType(MediaType.APPLICATION_JSON).content(noiseJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode terrain = objectMapper.readTree(generated);
        String startBody = objectMapper.writeValueAsString(java.util.Map.of(
                "resolution", RES,
                "heightmap", terrain.get("heightmap"),
                "noise", objectMapper.readTree(noiseJson)));
        String response = mvc.perform(post("/api/evolution")
                        .contentType(MediaType.APPLICATION_JSON).content(startBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("chainId").asText();
    }

    @Test
    void fullEvolutionLifecycleThroughApi() throws Exception {
        String chainId = startChainFromGeneratedTerrain();

        // 链摘要：根链血缘为 null，起始帧未冲刷故不判稳态
        mvc.perform(get("/api/evolution/" + chainId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameCount").value(1))
                .andExpect(jsonPath("$.resolution").value(RES))
                .andExpect(jsonPath("$.branchedFromChainId").value(nullValue()))
                .andExpect(jsonPath("$.branchedFromFrameIndex").value(nullValue()))
                .andExpect(jsonPath("$.steady").value(false))
                .andExpect(jsonPath("$.lastChange").value(nullValue()));

        // 推进两轮：前端只回传链标识与侵蚀参数
        String firstAdvance = mvc.perform(post("/api/evolution/" + chainId + "/advance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"erosion\": " + erosionJson(7) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value(1))
                .andExpect(jsonPath("$.heightmap", hasSize(RES * RES)))
                .andExpect(jsonPath("$.flow", hasSize(RES * RES)))
                .andExpect(jsonPath("$.stats.totalSteps").isNumber())
                .andExpect(jsonPath("$.params.seed").value(7))
                .andExpect(jsonPath("$.frameCount").value(2))
                .andExpect(jsonPath("$.threshold").value(0.02))
                .andReturn().getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(firstAdvance);
        assertEquals(1.0, first.get("flowChange").asDouble(), 0.0);
        assertFalse(first.get("steady").asBoolean());
        // 汇流守恒：逐轮场总和 == 该轮总步数
        long firstSteps = first.get("stats").get("totalSteps").asLong();
        double flowSum = 0;
        for (JsonNode v : first.get("flow")) {
            flowSum += v.asDouble();
        }
        assertEquals((double) firstSteps, flowSum, 0.0);

        String secondAdvance = mvc.perform(post("/api/evolution/" + chainId + "/advance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"erosion\": " + erosionJson(7) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value(2))
                .andExpect(jsonPath("$.frameCount").value(3))
                .andExpect(jsonPath("$.params.seed").value(7))
                .andReturn().getResponse().getContentAsString();
        JsonNode second = objectMapper.readTree(secondAdvance);
        assertTrue(second.get("flowChange").asDouble() < 1.0,
                "第二轮的累计汇流场变化必须小于首轮的 1.0（水系在收窄）");

        // 链详情：帧序、每帧参数与变化幅度
        mvc.perform(get("/api/evolution/" + chainId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameCount").value(3))
                .andExpect(jsonPath("$.branchedFromChainId").value(nullValue()))
                .andExpect(jsonPath("$.frames", hasSize(3)))
                .andExpect(jsonPath("$.frames[0].params").value(nullValue()))
                .andExpect(jsonPath("$.frames[0].flowChange").value(nullValue()))
                .andExpect(jsonPath("$.frames[1].params.seed").value(7))
                .andExpect(jsonPath("$.frames[2].params.seed").value(7))
                .andExpect(jsonPath("$.threshold").value(0.02));

        // 单独取回历史帧（第 0 帧与第 1 帧）
        mvc.perform(get("/api/evolution/" + chainId + "/frames/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value(0))
                .andExpect(jsonPath("$.heightmap", hasSize(RES * RES)))
                .andExpect(jsonPath("$.flow", hasSize(RES * RES)));
        mvc.perform(get("/api/evolution/" + chainId + "/frames/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value(1));

        // 稳态查询：结论与变化幅度、阈值一并给出，且二者严格自洽
        String steadyBody = mvc.perform(get("/api/evolution/" + chainId + "/steady"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainId").value(chainId))
                .andExpect(jsonPath("$.frameCount").value(3))
                .andExpect(jsonPath("$.threshold").value(0.02))
                .andExpect(jsonPath("$.change").value(notNullValue()))
                .andReturn().getResponse().getContentAsString();
        JsonNode steady = objectMapper.readTree(steadyBody);
        assertEquals(steady.get("change").asDouble() <= 0.02, steady.get("steady").asBoolean());

        // 从第 1 帧岔出新分支
        String branchResponse = mvc.perform(post("/api/evolution/" + chainId + "/branch")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frameIndex\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chain.frameCount").value(1))
                .andExpect(jsonPath("$.chain.branchedFromChainId").value(chainId))
                .andExpect(jsonPath("$.chain.branchedFromFrameIndex").value(1))
                .andExpect(jsonPath("$.frame.index").value(0))
                .andExpect(jsonPath("$.frame.flow", hasSize(RES * RES)))
                .andReturn().getResponse().getContentAsString();
        String branchId = objectMapper.readTree(branchResponse).get("chain").get("chainId").asText();

        // 新分支第 0 帧汇流场为零（本链尚未冲刷）
        double branchFrame0FlowSum = 0;
        for (JsonNode v : objectMapper.readTree(branchResponse).get("frame").get("flow")) {
            branchFrame0FlowSum += v.asDouble();
        }
        assertEquals(0.0, branchFrame0FlowSum, 0.0);

        // 在新分支上推进
        mvc.perform(post("/api/evolution/" + branchId + "/advance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"erosion\": " + erosionJson(99) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameCount").value(2))
                .andExpect(jsonPath("$.params.seed").value(99))
                .andExpect(jsonPath("$.flowChange").value(1.0));

        // 分支推进后父链依旧是 3 帧
        mvc.perform(get("/api/evolution/" + chainId))
                .andExpect(jsonPath("$.frameCount").value(3));

        // 两条链都在列表里，血缘字段正确
        mvc.perform(get("/api/evolution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.chainId == '" + chainId + "')].frameCount").value(hasItem(3)))
                .andExpect(jsonPath("$[?(@.chainId == '" + branchId + "')].branchedFromFrameIndex")
                        .value(hasItem(1)));

        // 把父链第 2 帧导出为命名快照，再经原快照接口取回
        mvc.perform(post("/api/evolution/" + chainId + "/frames/2/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"evo-frame-2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("evo-frame-2"));
        mvc.perform(get("/api/snapshots/evo-frame-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value(RES))
                .andExpect(jsonPath("$.heightmap", hasSize(RES * RES)))
                .andExpect(jsonPath("$.erosion.seed").value(7));
    }

    @Test
    void unknownChainReturns404WithReason() throws Exception {
        mvc.perform(get("/api/evolution/no-such-chain"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("演进链不存在")));
        mvc.perform(post("/api/evolution/no-such-chain/advance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"erosion\": " + erosionJson(7) + "}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/evolution/no-such-chain/steady"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/evolution/no-such-chain/branch")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frameIndex\": 0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownFrameReturns404WithReason() throws Exception {
        String chainId = startChainFromGeneratedTerrain();
        mvc.perform(get("/api/evolution/" + chainId + "/frames/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("不存在第 99 帧")));
        mvc.perform(post("/api/evolution/" + chainId + "/branch")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frameIndex\": 99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("不存在第 99 帧")));
    }

    @Test
    void invalidErosionParamsOnAdvanceAreRejectedBeforeComputation() throws Exception {
        String chainId = startChainFromGeneratedTerrain();
        String body = "{\"erosion\": {\"dropletCount\": 100, \"erodeRate\": -0.5, \"depositRate\": 0.3,"
                + " \"evaporateRate\": 0.02, \"gravity\": 4.0, \"capacityFactor\": 1.5,"
                + " \"baseFlow\": 0.01, \"inertia\": 0.05, \"maxSteps\": 32,"
                + " \"minWater\": 0.01, \"seed\": 7}}";
        mvc.perform(post("/api/evolution/" + chainId + "/advance")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("erodeRate")));
        // 被拒绝后链仍是 1 帧
        mvc.perform(get("/api/evolution/" + chainId))
                .andExpect(jsonPath("$.frameCount").value(1));
    }

    @Test
    void startingWithInvalidHeightmapIsRejected() throws Exception {
        String body = "{\"resolution\": 32, \"heightmap\": [0.1, 0.2], \"noise\": null}";
        mvc.perform(post("/api/evolution")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("高度场长度")));
    }
}
