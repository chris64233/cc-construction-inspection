package com.chris64233.constructioninspection;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 方案变更 Web 层冒烟测试：变更提交/批准、失效标记、过期版本拒绝、复检与最终批准。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AmendmentApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void amendmentProposalApprovalReinspectionAndFinalApprovalViaApi() throws Exception {
        MvcResult permitResult = mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "变更API许可", "stages": [
                                  {"name": "基础工程", "items": [{"code": "F1", "name": "地基"}]},
                                  {"name": "主体结构", "items": [{"code": "M1", "name": "混凝土"}]}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentPlanVersionNumber").value(1))
                .andReturn();
        long permitId = id(permitResult);

        JsonNode stages = read(mockMvc.perform(get("/api/permits/{id}/stages", permitId))
                .andExpect(status().isOk()).andReturn());
        long stage1Id = stages.get(0).get("id").asLong();
        long stage2Id = stages.get(1).get("id").asLong();
        long f1 = stages.get(0).get("items").get(0).get("id").asLong();
        long m1 = stages.get(1).get("items").get(0).get("id").asLong();

        // 完成两个阶段
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                .content(inspectionBody("SUB-F1", f1))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/stages/{id}/accept", stage1Id)).andExpect(status().isOk());
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                .content(inspectionBody("SUB-M1", m1))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/stages/{id}/accept", stage2Id)).andExpect(status().isOk());

        // 提交仅影响主体结构的变更
        MvcResult proposed = mockMvc.perform(post("/api/permits/{id}/amendments", permitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summary": "主体设计调整", "affectedStageIds": [%d]}
                                """.formatted(stage2Id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.basePlanVersionNumber").value(1))
                .andExpect(jsonPath("$.affectedStages[0].stageId").value(stage2Id))
                .andReturn();
        long amendmentId = id(proposed);

        // 批准：生成方案 v2，主体阶段 1 条结果失效、须复检
        mockMvc.perform(post("/api/amendments/{id}/approve", amendmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planVersionNumber").value(2))
                .andExpect(jsonPath("$.effects[0].invalidatedRecordCount").value(1))
                .andExpect(jsonPath("$.effects[0].newWorkVersionNumber").value(2))
                .andExpect(jsonPath("$.effects[0].mustReinspect").value(true));
        // 取新版本真实 id
        JsonNode versions = read(mockMvc.perform(get("/api/stages/{id}/versions", stage2Id))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].reason").value("AMENDMENT"))
                .andExpect(jsonPath("$[1].planVersionNumber").value(2))
                .andExpect(jsonPath("$[0].records[0].invalidated").value(true))
                .andExpect(jsonPath("$[0].records[0].invalidatedByPlanVersion").value(2))
                .andReturn());
        long v2Id = versions.get(1).get("id").asLong();

        // 方案版本历史
        mockMvc.perform(get("/api/permits/{id}/plan-versions", permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].reason").value("AMENDMENT"))
                .andExpect(jsonPath("$[1].amendmentId").value(amendmentId));

        // 携带旧版本提交检查 → 409 过期
        long v1Id = versions.get(0).get("id").asLong();
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "SUB-STALE", "itemDefinitionId": %d,
                                 "conclusion": "PASS", "inspector": "李四", "evidence": "旧版本",
                                 "expectedWorkVersionId": %d}
                                """.formatted(m1, v1Id)))
                .andExpect(status().isConflict());

        // 携带当前版本提交 → 通过，重新验收
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "SUB-M1-NEW", "itemDefinitionId": %d,
                                 "conclusion": "PASS", "inspector": "李四", "evidence": "新方案",
                                 "expectedWorkVersionId": %d}
                                """.formatted(m1, v2Id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invalidated").value(false));
        mockMvc.perform(post("/api/stages/{id}/accept", stage2Id)).andExpect(status().isOk());

        // 验收历史：主体阶段两次验收，分属方案 v1 / v2
        mockMvc.perform(get("/api/stages/{id}/acceptances", stage2Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].planVersionNumber").value(1))
                .andExpect(jsonPath("$[1].planVersionNumber").value(2));

        // 携带过期方案版本最终批准 → 409；携带当前版本 → 成功
        mockMvc.perform(post("/api/permits/{id}/final-approval", permitId)
                        .param("expectedPlanVersionNumber", "1"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/permits/{id}/final-approval", permitId)
                        .param("expectedPlanVersionNumber", "2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planVersionNumber").value(2));
        mockMvc.perform(get("/api/permits/{id}/final-approval", permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[1].planVersionNumber").value(2))
                .andExpect(jsonPath("$.stages[0].planVersionNumber").value(1));
    }

    private static String inspectionBody(String submissionNo, long itemId) {
        return """
                {"submissionNo": "%s", "itemDefinitionId": %d,
                 "conclusion": "PASS", "inspector": "张三", "evidence": "合格"}
                """.formatted(submissionNo, itemId);
    }

    private long id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
