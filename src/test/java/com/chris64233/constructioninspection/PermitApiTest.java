package com.chris64233.constructioninspection;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PermitApiTest {

    @Autowired
    MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    private String createPermitJson(String permitNo) {
        return """
                {
                  "permitNo": "%s",
                  "projectName": "示例工程",
                  "stages": [
                    {"sequence": 1, "name": "地基与基础", "items": [
                      {"itemCode": "A", "name": "基槽验收"},
                      {"itemCode": "B", "name": "钢筋隐蔽"}
                    ]},
                    {"sequence": 2, "name": "主体结构", "items": [
                      {"itemCode": "C", "name": "模板安装"}
                    ]}
                  ]
                }
                """.formatted(permitNo);
    }

    private String inspectionJson(String item, String conclusion, String subNo) {
        return """
                {"itemCode": "%s", "conclusion": "%s", "inspector": "检查员甲",
                 "evidence": "照片与记录", "submissionNo": "%s"}
                """.formatted(item, conclusion, subNo);
    }

    @Test
    void endToEndOverHttp() throws Exception {
        String permitNo = "WEB-" + SEQ.incrementAndGet();
        String location = mvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPermitJson(permitNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitNo").value(permitNo))
                .andExpect(jsonPath("$.stages", hasSize(2)))
                .andExpect(jsonPath("$.stages[0].currentVersion").value(1))
                .andReturn().getResponse().getContentAsString();

        long permitId = ((Number) JsonPath.read(location, "$.id")).longValue();
        String base = "/api/permits/" + permitId;

        // 前置阶段未验收，后续阶段不能申请检查
        mvc.perform(post(base + "/stages/2/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson("C", "PASS", "w1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PREVIOUS_STAGE_NOT_ACCEPTED"));

        // 阶段1：A 通过，B 不通过 → 生成整改项
        mvc.perform(post(base + "/stages/1/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson("A", "PASS", "w2")))
                .andExpect(status().isCreated());
        String failBody = mvc.perform(post(base + "/stages/1/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson("B", "FAIL", "w3")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rectification.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        long rectId = ((Number) JsonPath.read(failBody, "$.rectification.id")).longValue();

        // 整改未关闭不能验收
        mvc.perform(post(base + "/stages/1/acceptance"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECTIFICATIONS_OPEN"));

        // 整改提交 → 版本升到 2
        mvc.perform(post(base + "/stages/1/rectifications/" + rectId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionNo\": \"wr1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        mvc.perform(get(base + "/stages"))
                .andExpect(jsonPath("$[0].currentVersion").value(2));

        // 版本 2 复检：B 通过（整改关闭），A 重新通过
        mvc.perform(post(base + "/stages/1/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson("B", "PASS", "w4")))
                .andExpect(status().isCreated());
        mvc.perform(get(base + "/stages/1/rectifications"))
                .andExpect(jsonPath("$[0].status").value("CLOSED"))
                .andExpect(jsonPath("$[0].closedVersion").value(2));
        mvc.perform(post(base + "/stages/1/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson("A", "PASS", "w5")))
                .andExpect(status().isCreated());

        // 验收阶段1 → 阶段2 → 最终批准
        mvc.perform(post(base + "/stages/1/acceptance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post(base + "/stages/2/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson("C", "PASS", "w6")))
                .andExpect(status().isCreated());
        mvc.perform(post(base + "/stages/2/acceptance"))
                .andExpect(status().isOk());

        mvc.perform(get(base + "/final-approval"))
                .andExpect(status().isNotFound());
        mvc.perform(post(base + "/final-approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\": \"审批人甲\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get(base + "/final-approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approval.approvedBy").value("审批人甲"))
                .andExpect(jsonPath("$.stages", hasSize(2)))
                .andExpect(jsonPath("$.stopOrders", hasSize(0)));

        // 已批准后不能再签发停工令
        mvc.perform(post(base + "/stop-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"违规施工\", \"issuer\": \"监督员\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERMIT_APPROVED"));
    }

    @Test
    void validationRejectsBlankFields() throws Exception {
        mvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permitNo\": \"\", \"projectName\": \"\", \"stages\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
