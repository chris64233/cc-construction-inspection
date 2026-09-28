package com.chris64233.constructioninspection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web 层冒烟测试：许可创建、检查提交幂等、整改链与验收拦截 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PermitApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createPermitSubmitInspectAndQueryViaApi() throws Exception {
        // 创建许可
        MvcResult permitResult = mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "API测试许可", "stages": [
                                  {"name": "基础工程", "items": [{"code": "F1", "name": "地基"}]},
                                  {"name": "主体结构", "items": [{"code": "S1", "name": "混凝土"}]}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("API测试许可"))
                .andReturn();
        long permitId = objectMapper.readTree(permitResult.getResponse().getContentAsString())
                .get("id").asLong();

        // 查询许可阶段
        MvcResult stagesResult = mockMvc.perform(get("/api/permits/{id}/stages", permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andReturn();
        JsonNode stages = objectMapper.readTree(stagesResult.getResponse().getContentAsString());
        long stage1Id = stages.get(0).get("id").asLong();
        long itemId = stages.get(0).get("items").get(0).get("id").asLong();

        // 前置阶段未完成，后续阶段检查被拒绝
        long stage2ItemId = stages.get(1).get("items").get(0).get("id").asLong();
        mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "API-X", "itemDefinitionId": %d,
                                 "conclusion": "PASS", "inspector": "张三", "evidence": "照片",
                                 "planVersion": 1, "stageVersion": 1}
                                """.formatted(stage2ItemId)))
                .andExpect(status().isConflict());

        // 提交不通过检查 → 生成整改项
        mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "API-1", "itemDefinitionId": %d,
                                 "conclusion": "FAIL", "inspector": "张三", "evidence": "地基下沉",
                                 "planVersion": 1, "stageVersion": 1}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conclusion").value("FAIL"));

        // 相同提交号幂等：返回同一条记录
        MvcResult dup = mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "API-1", "itemDefinitionId": %d,
                                 "conclusion": "FAIL", "inspector": "张三", "evidence": "地基下沉",
                                 "planVersion": 1, "stageVersion": 1}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissionNo").value("API-1"))
                .andReturn();
        assertEquals("API-1", objectMapper.readTree(dup.getResponse().getContentAsString())
                .get("submissionNo").asText());

        // 整改链查询
        MvcResult rects = mockMvc.perform(get("/api/stages/{id}/rectifications", stage1Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].itemCode").value("F1"))
                .andReturn();
        long rectId = objectMapper.readTree(rects.getResponse().getContentAsString())
                .get(0).get("id").asLong();

        // 整改提交 → 产生复检版本 v2
        mockMvc.perform(post("/api/rectifications/{id}/submit", rectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"已加固\", \"planVersion\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.resultVersionNumber").value(2));

        mockMvc.perform(get("/api/stages/{id}/versions", stage1Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].reason").value("RECTIFICATION"));

        // 新版本未复检，验收被拒绝
        mockMvc.perform(post("/api/stages/{id}/accept", stage1Id))
                .andExpect(status().isConflict());

        // 最终批准因阶段未完成被拒绝
        mockMvc.perform(post("/api/permits/{id}/final-approval", permitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planVersion\": 1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/permits/{id}/final-approval", permitId))
                .andExpect(status().isNotFound());
    }

    @Test
    void amendmentFlowViaApi() throws Exception {
        // 创建许可：基础工程（F1）+ 主体结构（S1）
        MvcResult permitResult = mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "变更API许可", "stages": [
                                  {"name": "基础工程", "items": [{"code": "F1", "name": "地基"}]},
                                  {"name": "主体结构", "items": [{"code": "S1", "name": "混凝土"}]}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentPlanVersion").value(1))
                .andReturn();
        long permitId = objectMapper.readTree(permitResult.getResponse().getContentAsString())
                .get("id").asLong();
        MvcResult stagesResult = mockMvc.perform(get("/api/permits/{id}/stages", permitId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode stages = objectMapper.readTree(stagesResult.getResponse().getContentAsString());
        long stage1Id = stages.get(0).get("id").asLong();
        long stage2Id = stages.get(1).get("id").asLong();
        long f1Id = stages.get(0).get("items").get(0).get("id").asLong();
        long s1Id = stages.get(1).get("items").get(0).get("id").asLong();

        // 阶段1检查通过并验收
        mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "AM-1", "itemDefinitionId": %d,
                                 "conclusion": "PASS", "inspector": "张三", "evidence": "合格",
                                 "planVersion": 1, "stageVersion": 1}
                                """.formatted(f1Id)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/stages/{id}/accept", stage1Id))
                .andExpect(status().isOk());

        // 登记并批准变更：只影响阶段2
        MvcResult amendmentResult = mockMvc.perform(post("/api/permits/{id}/amendments", permitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "主体方案调整", "affectedStageIds": [%d]}
                                """.formatted(stage2Id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        long amendmentId = objectMapper.readTree(amendmentResult.getResponse().getContentAsString())
                .get("id").asLong();
        mockMvc.perform(post("/api/amendments/{id}/approve", amendmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.planVersionNumber").value(2));

        // 方案版本查询：v1 初始 + v2 变更
        mockMvc.perform(get("/api/permits/{id}/plan-versions", permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].amendmentId").value(amendmentId));

        // 基于旧方案版本的检查提交被拒绝（409）
        mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "AM-STALE", "itemDefinitionId": %d,
                                 "conclusion": "PASS", "inspector": "张三", "evidence": "合格",
                                 "planVersion": 1, "stageVersion": 1}
                                """.formatted(s1Id)))
                .andExpect(status().isConflict());

        // 按新方案提交检查并验收阶段2（变更已为阶段2产生 v2 工程版本）
        mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "AM-2", "itemDefinitionId": %d,
                                 "conclusion": "PASS", "inspector": "张三", "evidence": "合格",
                                 "planVersion": 2, "stageVersion": 2}
                                """.formatted(s1Id)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/stages/{id}/accept", stage2Id))
                .andExpect(status().isOk());

        // 基于旧方案版本的最终批准被拒绝；按当前方案版本批准成功
        mockMvc.perform(post("/api/permits/{id}/final-approval", permitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planVersion\": 1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/permits/{id}/final-approval", permitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planVersion\": 2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planVersionNumber").value(2));
        mockMvc.perform(get("/api/permits/{id}/final-approval", permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value(org.hamcrest.Matchers.containsString("方案版本 v2")));
    }
}
