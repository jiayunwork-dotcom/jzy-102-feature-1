package com.geoviz.terrain.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 演进接口层测试：开启/推进/查询/分支/取帧/导出快照的正常往返，
 * 以及不存在的链/帧 → 404、非法侵蚀参数 → 400、越界帧序号岔分支 → 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvolutionApiTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String readId(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    private static String erosionJson(long seed) {
        return """
                {"dropletCount": 400, "erodeRate": 0.3, "depositRate": 0.3,
                 "evaporateRate": 0.02, "gravity": 4.0, "capacityFactor": 1.5,
                 "baseFlow": 0.01, "inertia": 0.05, "maxSteps": 24,
                 "minWater": 0.01, "seed": %d}
                """.formatted(seed);
    }

    private static String startJson() {
        return """
                {"resolution": 32, "noise": null, "heightmap": [%s]}
                """.formatted("0.5,".repeat(32 * 32 - 1) + "0.5");
    }

    private String startChain() throws Exception {
        MvcResult result = mvc.perform(post("/api/evolutions")
                        .contentType(MediaType.APPLICATION_JSON).content(startJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.frameCount").value(1))
                .andExpect(jsonPath("$.headIndex").value(0))
                .andExpect(jsonPath("$.parentChainId").doesNotExist())
                .andExpect(jsonPath("$.frames", hasSize(1)))
                .andExpect(jsonPath("$.frames[0].flowChange").doesNotExist())
                .andReturn();
        return readId(result);
    }

    @Test
    void startAdvanceQueryAndGetFrameRoundTrip() throws Exception {
        String id = startChain();

        // 推进两轮
        MvcResult r1 = mvc.perform(post("/api/evolutions/" + id + "/advance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"erosion\": " + erosionJson(7) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head.index").value(1))
                .andExpect(jsonPath("$.head.heightmap", hasSize(32 * 32)))
                .andExpect(jsonPath("$.head.flowAccumulation", hasSize(32 * 32)))
                .andExpect(jsonPath("$.head.roundFlow", hasSize(32 * 32)))
                .andExpect(jsonPath("$.chain.frameCount").value(2))
                .andReturn();
        JsonNode n1 = objectMapper.readTree(r1.getResponse().getContentAsString());
        long roundFlowTotal1 = n1.path("head").path("roundFlowTotal").asLong();
        long totalSteps1 = n1.path("head").path("stats").path("totalSteps").asLong();
        org.junit.jupiter.api.Assertions.assertTrue(roundFlowTotal1 > 0);
        // 守恒：本轮流经场总和 == 该轮实际步数
        org.junit.jupiter.api.Assertions.assertEquals(totalSteps1, roundFlowTotal1);

        mvc.perform(post("/api/evolutions/" + id + "/advance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"erosion\": " + erosionJson(7) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head.index").value(2))
                .andExpect(jsonPath("$.chain.frameCount").value(3));

        // 查整条链：帧数量、每帧参数与变化幅度、阈值
        mvc.perform(get("/api/evolutions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameCount").value(3))
                .andExpect(jsonPath("$.frames", hasSize(3)))
                .andExpect(jsonPath("$.frames[1].erosion.seed").value(7))
                .andExpect(jsonPath("$.frames[2].erosion.seed").value(7))
                .andExpect(jsonPath("$.frames[2].flowChange").isNumber())
                .andExpect(jsonPath("$.convergenceThreshold").value(0.03))
                .andExpect(jsonPath("$.currentFlowChange").isNumber());

        // 单独取回历史帧
        mvc.perform(get("/api/evolutions/" + id + "/frames/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value(1))
                .andExpect(jsonPath("$.heightmap", hasSize(32 * 32)))
                .andExpect(jsonPath("$.flowAccumulation", hasSize(32 * 32)));
    }

    @Test
    void branchCarriesLineageAndIsListed() throws Exception {
        String id = startChain();
        mvc.perform(post("/api/evolutions/" + id + "/advance")
                .contentType(MediaType.APPLICATION_JSON).content("{\"erosion\": " + erosionJson(7) + "}"))
                .andExpect(status().isOk());

        MvcResult branchResult = mvc.perform(post("/api/evolutions/" + id + "/branch")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frameIndex\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentChainId").value(id))
                .andExpect(jsonPath("$.parentFrameIndex").value(1))
                .andExpect(jsonPath("$.branch").value(true))
                .andExpect(jsonPath("$.frameCount").value(1))
                .andReturn();
        String branchId = readId(branchResult);

        // 分支推进后，父链仍只有 2 帧
        mvc.perform(post("/api/evolutions/" + branchId + "/advance")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"erosion\": " + erosionJson(9) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head.index").value(1));

        mvc.perform(get("/api/evolutions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameCount").value(2));

        MvcResult listResult = mvc.perform(get("/api/evolutions"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        boolean listed = false;
        for (JsonNode c : list) {
            if (branchId.equals(c.path("id").asText())) {
                listed = true;
                org.junit.jupiter.api.Assertions.assertEquals(id, c.path("parentChainId").asText());
                org.junit.jupiter.api.Assertions.assertEquals(1, c.path("parentFrameIndex").asInt());
                org.junit.jupiter.api.Assertions.assertTrue(c.path("branch").asBoolean());
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(listed, "分支应出现在链列表中");
    }

    @Test
    void exportFrameAsSnapshotRoundTrip() throws Exception {
        String id = startChain();
        mvc.perform(post("/api/evolutions/" + id + "/advance")
                .contentType(MediaType.APPLICATION_JSON).content("{\"erosion\": " + erosionJson(7) + "}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/evolutions/" + id + "/frames/1/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"evo-frame-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("evo-frame-1"))
                .andExpect(jsonPath("$.resolution").value(32));

        mvc.perform(get("/api/snapshots/evo-frame-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heightmap", hasSize(32 * 32)))
                .andExpect(jsonPath("$.erosion.seed").value(7));
    }

    @Test
    void unknownChainAndFrameAre404() throws Exception {
        mvc.perform(get("/api/evolutions/no-such-chain"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("演进链不存在")));

        mvc.perform(post("/api/evolutions/no-such-chain/advance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"erosion\": " + erosionJson(7) + "}"))
                .andExpect(status().isNotFound());

        String id = startChain();
        mvc.perform(get("/api/evolutions/" + id + "/frames/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("不存在帧")));

        // 越界帧序号岔分支 → 404（帧不在范围内）
        mvc.perform(post("/api/evolutions/" + id + "/branch")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frameIndex\": 9}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidAdvanceParamsAre400AndCreateNoFrame() throws Exception {
        String id = startChain();
        String badErosion = """
                {"erosion": {"dropletCount": 100, "erodeRate": -0.5, "depositRate": 0.3,
                             "evaporateRate": 0.02, "gravity": 4.0, "capacityFactor": 1.5,
                             "baseFlow": 0.01, "inertia": 0.05, "maxSteps": 32,
                             "minWater": 0.01, "seed": 7}}
                """;
        mvc.perform(post("/api/evolutions/" + id + "/advance")
                        .contentType(MediaType.APPLICATION_JSON).content(badErosion))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("erodeRate")));

        // 非法推进不得改变链
        mvc.perform(get("/api/evolutions/" + id))
                .andExpect(jsonPath("$.frameCount").value(1));
    }

    @Test
    void startWithBadHeightmapIs400() throws Exception {
        String body = """
                {"resolution": 32, "noise": null, "heightmap": [0.1, 0.2]}
                """;
        mvc.perform(post("/api/evolutions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("高度场长度")));
    }

    @Test
    void missingErosionBodyAndMissingFrameIndexAre400() throws Exception {
        String id = startChain();

        // 推进时缺侵蚀参数 → 400，而不是 500
        mvc.perform(post("/api/evolutions/" + id + "/advance")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("侵蚀参数")));

        // 岔分支时缺 frameIndex → 400
        mvc.perform(post("/api/evolutions/" + id + "/branch")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("frameIndex")));

        // 导出快照时缺名称 → 400
        mvc.perform(post("/api/evolutions/" + id + "/frames/0/snapshot")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("快照名称")));
    }
}
