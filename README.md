# cc-construction-inspection

建设许可、施工阶段与检查资料管理服务。

实现建设许可按施工阶段的检查、整改与最终使用批准全流程管理。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run

## 领域模型

- **许可（Permit）**：一个建设工程许可，包含有顺序的施工阶段。
- **阶段（Stage）**：按 `sequence` 排序，定义本阶段所需的检查项；维护当前工程版本 `currentVersion`（从 1 开始）。
- **检查记录（InspectionRecord）**：包含结论（PASS/FAIL）、检查人、证据、提交号和被检查的工程版本。
- **整改项（Rectification）**：不通过检查自动生成，状态流转 OPEN → SUBMITTED → CLOSED。
- **停工令（StopWorkOrder）**：ACTIVE / LIFTED。
- **最终批准（FinalApproval）**：每个许可最多一条，创建后不可修改。

## 主要业务规则

1. **阶段顺序**：前置阶段未验收完成时，不得申请后续阶段的检查或验收（`PREVIOUS_STAGE_NOT_ACCEPTED`）。
2. **检查与版本**：检查记录登记在当前工程版本上。同一检查项在同一版本只能有一个生效结论
   （数据库唯一约束 `(stage_id, item_code, version)` 兜底，重复结论返回 `CONCLUSION_EXISTS`）。
3. **整改链**：检查不通过必须生成整改项；整改提交（`submit`）后阶段工程版本 +1，产生新的复检版本；
   新版本上该检查项复检通过后整改项自动关闭。版本升级后，旧版本上的通过结论全部过期。
4. **阶段一次性验收**：当前版本的所有检查项均通过、且不存在未关闭（OPEN/SUBMITTED）的整改项时，
   阶段才能验收完成，两个条件在同一事务内校验。
5. **幂等**：检查提交号在阶段内唯一（`(stage_id, submission_no)`），重复提交返回首次结果；
   整改提交号同理，重复提交不重复提升版本。
6. **并发控制**：检查提交、整改提交、阶段验收共用阶段行悲观写锁串行化，验收永远基于最新版本状态，
   不会基于过期结果验收；并发复检同一检查项同一版本时只有一条生效结论。
7. **最终使用批准**：要求全部阶段验收完成且不存在活动停工令。批准与停工令签发共用许可行悲观写锁，
   并发时只形成"已批准"或"被阻止"其中一种结果；批准后不可再签发停工令，批准记录不可修改，
   重复批准申请幂等返回原记录。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/permits` | 创建许可（含阶段与检查项定义） |
| GET | `/api/permits/{id}` | 许可详情（含各阶段状态与当前版本） |
| GET | `/api/permits/{id}/stages` | 许可阶段查询 |
| POST | `/api/permits/{id}/stages/{seq}/inspections` | 提交检查（幂等） |
| POST | `/api/permits/{id}/stages/{seq}/rectifications/{rid}/submit` | 整改提交（产生新复检版本） |
| POST | `/api/permits/{id}/stages/{seq}/acceptance` | 阶段验收 |
| GET | `/api/permits/{id}/stages/{seq}/inspections?version=` | 检查版本查询 |
| GET | `/api/permits/{id}/stages/{seq}/rectifications` | 整改链查询 |
| POST | `/api/permits/{id}/stop-orders` | 签发停工令 |
| POST | `/api/permits/{id}/stop-orders/{oid}/lift` | 解除停工令 |
| POST | `/api/permits/{id}/final-approval` | 最终使用批准（幂等） |
| GET | `/api/permits/{id}/final-approval` | 最终批准依据查询（批准记录 + 阶段验收情况 + 停工令历史） |

错误响应统一为 `{"code", "message", "timestamp"}`，业务冲突返回 409，前置条件不满足返回 422。

## 测试

- `InspectionFlowTest`：服务层集成测试，覆盖阶段顺序、整改链、版本过期、幂等、
  以及"批准 vs 停工令""并发复检"两个并发用例。
- `PermitApiTest`：HTTP 端到端流程与参数校验。
