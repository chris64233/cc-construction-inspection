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
                                 "conclusion": "PASS", "inspector": "张三", "evidence": "照片"}
                                """.formatted(stage2ItemId)))
                .andExpect(status().isConflict());

        // 提交不通过检查 → 生成整改项
        mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "API-1", "itemDefinitionId": %d,
                                 "conclusion": "FAIL", "inspector": "张三", "evidence": "地基下沉"}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conclusion").value("FAIL"));

        // 相同提交号幂等：返回同一条记录
        MvcResult dup = mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionNo": "API-1", "itemDefinitionId": %d,
                                 "conclusion": "FAIL", "inspector": "张三", "evidence": "地基下沉"}
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
                        .content("{\"note\": \"已加固\"}"))
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
        mockMvc.perform(post("/api/permits/{id}/final-approval", permitId))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/permits/{id}/final-approval", permitId))
                .andExpect(status().isNotFound());
    }
}
