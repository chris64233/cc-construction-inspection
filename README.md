# cc-construction-inspection

建设许可、施工阶段与检查资料管理服务。

实现建设许可按施工阶段完成检查、整改和最终使用批准的全流程管理。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 主要业务规则

1. **许可与阶段定义**：创建许可时按顺序定义施工阶段，每个阶段定义所需检查项。
   第一个阶段激活（ACTIVE）并生成初始工程版本 v1，其余阶段为 PENDING。
2. **检查记录**：每条检查记录包含结论（PASS/FAIL）、检查人、证据和被检查的工程版本。
   只有前置阶段全部验收完成、当前阶段处于 ACTIVE 时，才能提交该阶段的检查。
3. **整改与复检版本**：结论不通过的检查自动生成整改项（OPEN）；整改提交后整改项关闭，
   并为阶段产生新的复检版本（v2、v3…），形成"不通过记录 → 整改项 → 新版本"的整改链。
4. **阶段一次性验收**：只有当前（最新）工程版本上所有检查项都通过、且阶段内整改项全部关闭，
   阶段才能验收完成；验收通过后自动激活下一阶段。验收读取的是最新版本，
   旧版本上的通过结论不构成验收依据（不基于过期结果）。
5. **幂等与唯一结论**：检查提交号（submissionNo）全局唯一，重复提交返回首次记录；
   同一检查项在同一工程版本上只允许一条生效结论（数据库唯一约束兜底）。
6. **并发一致性**：检查提交、整改关闭、阶段验收、方案变更批准共用阶段行级悲观锁；
   最终批准、停工令签发、方案变更批准共用许可行级悲观锁，并发时只形成一种结果。
   此外，检查提交可携带 `expectedWorkVersionId`、最终批准可携带 `expectedPlanVersionNumber`、
   变更批准可携带 `expectedBasePlanVersionNumber`，方案/检查版本已演进时显式拒绝过期操作。
7. **最终使用批准**：要求全部阶段验收完成且不存在活动停工令，且只引用当前方案版本与有效检查结果
   （任一阶段验收版本上存在被失效结果即拒绝）。批准记录一经形成不可修改：不可重复批准，
   批准后不可再签发停工令、不可再提交方案变更。
8. **方案变更（部分阶段失效与保留）**：
   - 变更单提交时标明受影响施工阶段（可只影响部分阶段），并快照基准方案版本；
   - 批准后在同一事务内生成新方案版本（v2、v3…），**仅**使受影响阶段的当前有效检查结果失效
     （记录保留，登记 `invalidatedByPlanVersion`），未受影响阶段的有效结果与验收状态原样保留；
   - 已开工的受影响阶段产生新的 `AMENDMENT` 工程版本，必须按新版本重新检查并重新验收；
     其未关闭整改项随旧方案取消（`CANCELLED`，已关闭整改链保留为历史）；
   - 受影响且已完成的阶段被打回：前置阶段未就绪时为 `SUSPENDED`（不可提交检查），
     前置阶段重新验收后自动恢复为 `ACTIVE`；尚未开工的 `PENDING` 阶段不提前生成版本，
     待激活时按当前方案生成初始版本；
   - 每次阶段验收追加一条不可变的验收记录，关联当时的方案版本与工程版本（复检结果），
     方案版本反向关联变更单，最终批准依据快照记录每阶段的方案/工程版本，形成完整可追溯链。

## 方案与检查版本

- **方案版本（PlanVersion，许可级）**：许可创建时为 v1，每次变更批准 +1；
  `AMENDMENT` 版本反向关联批准它的变更单。
- **工程版本（WorkVersion，阶段级）**：每个工程版本带 `planVersionNumber` 快照，
  来源为 `INITIAL` / `RECTIFICATION` / `AMENDMENT`。方案变更后受影响阶段的新版本属于新方案。
- **检查结果有效性**：`InspectionRecord.invalidated` 标识结果是否已因方案变更失效。
  阶段验收与最终批准只统计当前版本上的有效（未失效）结果。


## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/permits` | 创建许可（含阶段与检查项定义，建立方案版本 v1） |
| GET | `/api/permits/{id}/stages` | 许可阶段查询 |
| POST | `/api/inspections` | 提交检查结论（幂等提交号；可选 `expectedWorkVersionId` 版本保护） |
| POST | `/api/rectifications/{id}/submit` | 整改提交（关闭整改项并产生复检版本） |
| POST | `/api/stages/{id}/accept` | 阶段一次性验收 |
| GET | `/api/stages/{id}/versions` | 检查版本查询（含各版本检查记录及失效标记） |
| GET | `/api/stages/{id}/rectifications` | 整改链查询（含 CANCELLED 状态） |
| GET | `/api/stages/{id}/acceptances` | 阶段验收历史（每次验收一条，关联方案/工程版本） |
| POST | `/api/permits/{id}/amendments` | 提交方案变更（`summary` + `affectedStageIds`） |
| POST | `/api/amendments/{id}/approve` | 批准变更（生成新方案版本；可选 `expectedBasePlanVersionNumber`） |
| GET | `/api/permits/{id}/amendments` | 许可的变更单列表 |
| GET | `/api/amendments/{id}` | 变更单详情（含受影响阶段） |
| GET | `/api/permits/{id}/plan-versions` | 方案版本历史（AMENDMENT 版本关联变更单） |
| POST | `/api/permits/{id}/stop-work-orders` | 签发停工令 |
| POST | `/api/stop-work-orders/{id}/lift` | 解除停工令 |
| POST | `/api/permits/{id}/final-approval` | 最终使用批准（可选 `expectedPlanVersionNumber` 版本保护） |
| GET | `/api/permits/{id}/final-approval` | 最终批准依据查询（批准快照 + 各阶段方案/工程版本 + 停工令历史） |

业务规则冲突（含过期版本操作）返回 409，资源不存在返回 404，参数不合法返回 400。

### 典型变更流程

1. 许可若干阶段已检查/验收，调用 `POST /api/permits/{id}/amendments` 提交变更并标明受影响阶段。
2. 调用 `POST /api/amendments/{id}/approve` 批准：返回的 `effects` 逐项说明每个受影响阶段
   失效检查数（`invalidatedRecordCount`）、取消整改数（`cancelledRectificationCount`）、
   新工程版本号（`newWorkVersionNumber`）及是否必须复检（`mustReinspect`）。
3. 受影响阶段在新版本上重新提交检查（携带新版本 id 作为 `expectedWorkVersionId`），
   旧版本提交返回 409；全部通过后重新验收，未受影响阶段无需任何操作。
4. 全部阶段在当前方案下验收完成后，携带 `expectedPlanVersionNumber` 发起最终批准；
   批准期间方案若已演进，请求返回 409，须复检受影响阶段后重试。


## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run
