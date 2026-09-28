package com.chris64233.constructioninspection;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.domain.AmendmentStatus;
import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.FinalApproval;
import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PlanVersion;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.VersionReason;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.service.AmendmentService;
import com.chris64233.constructioninspection.service.ApprovalService;
import com.chris64233.constructioninspection.service.InspectionService;
import com.chris64233.constructioninspection.service.PermitService;
import com.chris64233.constructioninspection.support.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 方案变更流程测试：部分阶段失效、未受影响结果保留、过期操作拒绝、关联完整 */
@SpringBootTest
@Transactional
class AmendmentWorkflowTest {

    @Autowired
    PermitService permitService;
    @Autowired
    InspectionService inspectionService;
    @Autowired
    ApprovalService approvalService;
    @Autowired
    AmendmentService amendmentService;
    @Autowired
    ConstructionStageRepository stageRepository;
    @Autowired
    PermitRepository permitRepository;

    Long permitId;
    Long stage1Id;
    Long stage2Id;
    Long stage3Id;
    List<InspectionItemDefinition> stage1Items;
    List<InspectionItemDefinition> stage2Items;
    List<InspectionItemDefinition> stage3Items;

    @BeforeEach
    void setUp() {
        Permit permit = permitService.createPermit("变更测试许可", List.of(
                new PermitService.StageDef("基础工程", List.of(
                        new PermitService.ItemDef("F1", "地基承载力"),
                        new PermitService.ItemDef("F2", "钢筋绑扎"))),
                new PermitService.StageDef("主体结构", List.of(
                        new PermitService.ItemDef("S1", "混凝土强度"))),
                new PermitService.StageDef("装饰装修", List.of(
                        new PermitService.ItemDef("D1", "墙面平整度")))));
        permitId = permit.getId();
        var stages = permitService.listStages(permitId);
        stage1Id = stages.get(0).getId();
        stage2Id = stages.get(1).getId();
        stage3Id = stages.get(2).getId();
        stage1Items = permitService.listItemDefinitions(stage1Id);
        stage2Items = permitService.listItemDefinitions(stage2Id);
        stage3Items = permitService.listItemDefinitions(stage3Id);
    }

    @Test
    void amendmentApproval_invalidatesOnlyAffectedStages() {
        // 阶段1验收完成；阶段2已有一条通过结论
        passAndAccept(stage1Id, stage1Items, 1, 1, "A");
        inspectionService.submitInspection("SUB-S1", stage2Items.get(0).getId(),
                Conclusion.PASS, "张三", "合格", 1, 1);
        Long stage1AcceptedVersionId = stageRepository.findById(stage1Id).orElseThrow()
                .getAcceptedVersionId();

        Amendment amendment = amendmentService.createAmendment(permitId, "主体方案调整", List.of(stage2Id));
        amendmentService.approveAmendment(amendment.getId(), 1);

        // 生成新方案版本 v2，并与变更单关联
        Permit permit = permitRepository.findById(permitId).orElseThrow();
        assertEquals(2, permit.getCurrentPlanVersionNumber());
        List<PlanVersion> planVersions = amendmentService.listPlanVersions(permitId);
        assertEquals(2, planVersions.size());
        assertNull(planVersions.get(0).getAmendment());
        assertEquals(amendment.getId(), planVersions.get(1).getAmendment().getId());
        assertEquals(AmendmentStatus.APPROVED, amendment.getStatus());
        assertNotNull(amendment.getApprovedAt());

        // 受影响阶段：产生 AMENDMENT 新版本并回到 ACTIVE，须重新检查
        ConstructionStage stage2 = stageRepository.findById(stage2Id).orElseThrow();
        assertEquals(StageStatus.ACTIVE, stage2.getStatus());
        assertNull(stage2.getAcceptedVersionId());
        List<WorkVersion> stage2Versions = inspectionService.listVersions(stage2Id);
        assertEquals(2, stage2Versions.size());
        WorkVersion amendmentVersion = stage2Versions.get(1);
        assertEquals(VersionReason.AMENDMENT, amendmentVersion.getReason());
        assertEquals(2, amendmentVersion.getPlanVersion().getVersionNumber());
        // 旧版本上的通过记录保留为历史，但不再构成验收依据
        assertEquals(1, inspectionService.listRecordsByVersions(
                List.of(stage2Versions.get(0).getId())).size());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> inspectionService.acceptStage(stage2Id));
        assertTrue(ex.getMessage().contains("尚未检查"));

        // 未受影响阶段：状态与有效结果完整保留
        ConstructionStage stage1 = stageRepository.findById(stage1Id).orElseThrow();
        assertEquals(StageStatus.COMPLETED, stage1.getStatus());
        assertEquals(stage1AcceptedVersionId, stage1.getAcceptedVersionId());
        assertEquals(1, inspectionService.listVersions(stage1Id).size());
        ConstructionStage stage3 = stageRepository.findById(stage3Id).orElseThrow();
        assertEquals(StageStatus.PENDING, stage3.getStatus());
    }

    @Test
    void affectedCompletedStage_mustBeReinspectedBeforeFinalApproval() {
        completeAllStages(1);

        Amendment amendment = amendmentService.createAmendment(permitId, "基础方案调整", List.of(stage1Id));
        amendmentService.approveAmendment(amendment.getId(), 1);

        // 已验收的受影响阶段回退为 ACTIVE；未受影响阶段保持 COMPLETED
        assertEquals(StageStatus.ACTIVE, stageRepository.findById(stage1Id).orElseThrow().getStatus());
        assertEquals(StageStatus.COMPLETED, stageRepository.findById(stage2Id).orElseThrow().getStatus());
        assertEquals(StageStatus.COMPLETED, stageRepository.findById(stage3Id).orElseThrow().getStatus());
        Long stage2AcceptedVersionId = stageRepository.findById(stage2Id).orElseThrow()
                .getAcceptedVersionId();

        // 失效阶段未重新验收前，最终批准被拒绝
        BusinessException ex = assertThrows(BusinessException.class,
                () -> approvalService.approve(permitId, 2));
        assertTrue(ex.getMessage().contains("未完成阶段"));

        // 按新方案版本重新检查并验收
        passAndAccept(stage1Id, stage1Items, 2, 2, "R");
        // 重新验收不回退未受影响的后续阶段
        ConstructionStage stage2 = stageRepository.findById(stage2Id).orElseThrow();
        assertEquals(StageStatus.COMPLETED, stage2.getStatus());
        assertEquals(stage2AcceptedVersionId, stage2.getAcceptedVersionId());

        FinalApproval approval = approvalService.approve(permitId, 2);
        assertEquals(2, approval.getPlanVersionNumber());
        assertTrue(approval.getBasis().contains("方案版本 v2"));
    }

    @Test
    void staleOperationsRejected_afterAmendmentApproved() {
        // 阶段1一条不通过结论 → 整改项 OPEN
        inspectionService.submitInspection("SUB-F1", stage1Items.get(0).getId(),
                Conclusion.FAIL, "张三", "地基下沉", 1, 1);
        Rectification rectification = inspectionService.listRectificationChain(stage1Id).get(0);

        Amendment amendment = amendmentService.createAmendment(permitId, "基础方案调整", List.of(stage1Id));
        amendmentService.approveAmendment(amendment.getId(), 1);

        // 基于旧方案版本的检查提交被拒绝
        BusinessException stalePlan = assertThrows(BusinessException.class, () ->
                inspectionService.submitInspection("SUB-STALE-1", stage1Items.get(1).getId(),
                        Conclusion.PASS, "李四", "合格", 1, 2));
        assertTrue(stalePlan.getMessage().contains("方案已变更"));
        // 基于旧工程版本的检查提交被拒绝
        BusinessException staleStage = assertThrows(BusinessException.class, () ->
                inspectionService.submitInspection("SUB-STALE-2", stage1Items.get(1).getId(),
                        Conclusion.PASS, "李四", "合格", 2, 1));
        assertTrue(staleStage.getMessage().contains("已过期"));
        // 基于旧方案版本的整改提交被拒绝
        BusinessException staleRect = assertThrows(BusinessException.class, () ->
                inspectionService.submitRectification(rectification.getId(), "已整改", 1));
        assertTrue(staleRect.getMessage().contains("方案已变更"));
        // 基于旧方案版本的最终批准被拒绝
        BusinessException staleApproval = assertThrows(BusinessException.class,
                () -> approvalService.approve(permitId, 1));
        assertTrue(staleApproval.getMessage().contains("方案已变更"));

        // 按新方案版本与工程版本提交则正常受理
        inspectionService.submitInspection("SUB-NEW-1", stage1Items.get(0).getId(),
                Conclusion.PASS, "李四", "复检合格", 2, 2);
    }

    @Test
    void amendmentApproval_voidsOpenRectificationsOfAffectedStages() {
        inspectionService.submitInspection("SUB-F1", stage1Items.get(0).getId(),
                Conclusion.FAIL, "张三", "地基下沉", 1, 1);
        Rectification rectification = inspectionService.listRectificationChain(stage1Id).get(0);
        assertEquals(RectificationStatus.OPEN, rectification.getStatus());

        Amendment amendment = amendmentService.createAmendment(permitId, "基础方案调整", List.of(stage1Id));
        amendmentService.approveAmendment(amendment.getId(), 1);

        // 未关闭整改项随旧方案作废，并关联到变更产生的新工程版本
        Rectification voided = inspectionService.listRectificationChain(stage1Id).get(0);
        assertEquals(RectificationStatus.CLOSED, voided.getStatus());
        assertTrue(voided.getNote().contains("作废"));
        WorkVersion amendmentVersion = inspectionService.listVersions(stage1Id).get(1);
        assertEquals(amendmentVersion.getId(), voided.getResultVersion().getId());

        // 重新检查全部通过后可直接验收，不被已作废的整改项阻塞
        passAndAccept(stage1Id, stage1Items, 2, 2, "R");
    }

    @Test
    void amendmentValidationRules() {
        // 受影响阶段不能为空、必须属于本许可
        assertThrows(BusinessException.class,
                () -> amendmentService.createAmendment(permitId, "空变更", List.of()));
        Permit other = permitService.createPermit("其他许可", List.of(
                new PermitService.StageDef("阶段", List.of(new PermitService.ItemDef("X1", "项")))));
        Long otherStageId = permitService.listStages(other.getId()).get(0).getId();
        assertThrows(BusinessException.class,
                () -> amendmentService.createAmendment(permitId, "越权变更", List.of(otherStageId)));

        Amendment amendment = amendmentService.createAmendment(permitId, "主体方案调整", List.of(stage2Id));
        // 方案版本不匹配时拒绝过期批准
        assertThrows(BusinessException.class,
                () -> amendmentService.approveAmendment(amendment.getId(), 99));
        amendmentService.approveAmendment(amendment.getId(), 1);
        // 不可重复批准
        assertThrows(BusinessException.class,
                () -> amendmentService.approveAmendment(amendment.getId(), 2));

        // 最终批准后不可再发起或批准变更
        passAndAccept(stage1Id, stage1Items, 2, 1, "B");
        passAndAccept(stage2Id, stage2Items, 2, 1, "C");
        passAndAccept(stage3Id, stage3Items, 2, 1, "D");
        approvalService.approve(permitId, 2);
        assertThrows(BusinessException.class,
                () -> amendmentService.createAmendment(permitId, "事后变更", List.of(stage1Id)));
        Amendment late = amendmentRepository_saveLateAmendment();
        assertThrows(BusinessException.class,
                () -> amendmentService.approveAmendment(late.getId(), 2));
    }

    @Test
    void pendingStageAmendment_takesEffectOnActivation() {
        // 变更只影响尚未激活的阶段3：无检查结果可失效，阶段保持 PENDING
        Amendment amendment = amendmentService.createAmendment(permitId, "装修方案调整", List.of(stage3Id));
        amendmentService.approveAmendment(amendment.getId(), 1);
        assertEquals(StageStatus.PENDING, stageRepository.findById(stage3Id).orElseThrow().getStatus());
        assertTrue(inspectionService.listVersions(stage3Id).isEmpty());

        // 阶段3后续激活时，初始工程版本归属新方案版本 v2
        passAndAccept(stage1Id, stage1Items, 2, 1, "B");
        passAndAccept(stage2Id, stage2Items, 2, 1, "C");
        List<WorkVersion> stage3Versions = inspectionService.listVersions(stage3Id);
        assertEquals(1, stage3Versions.size());
        assertEquals(VersionReason.INITIAL, stage3Versions.get(0).getReason());
        assertEquals(2, stage3Versions.get(0).getPlanVersion().getVersionNumber());
    }

    @Test
    void approvalBasisLinksPlanVersionAndAcceptedVersions() {
        completeAllStages(1);
        Amendment amendment = amendmentService.createAmendment(permitId, "基础方案调整", List.of(stage1Id));
        amendmentService.approveAmendment(amendment.getId(), 1);
        passAndAccept(stage1Id, stage1Items, 2, 2, "R");
        approvalService.approve(permitId, 2);

        var basis = approvalService.getApprovalBasis(permitId);
        assertEquals(2, basis.approval().getPlanVersionNumber());
        assertTrue(basis.approval().getBasis().contains("方案版本 v2"));
        // 阶段1按变更后的 v2 验收，其余阶段仍按原验收版本
        assertEquals(2, basis.stages().get(0).acceptedVersionNumber());
        assertEquals(1, basis.stages().get(1).acceptedVersionNumber());
        assertEquals(1, basis.stages().get(2).acceptedVersionNumber());
    }

    /** 模拟"登记先于最终批准、批准晚到"的并发场景：变更单登记后许可被最终批准 */
    private Amendment amendmentRepository_saveLateAmendment() {
        Permit permit2 = permitService.createPermit("迟到的变更许可", List.of(
                new PermitService.StageDef("阶段", List.of(new PermitService.ItemDef("X1", "项")))));
        Long p2Stage = permitService.listStages(permit2.getId()).get(0).getId();
        Amendment late = amendmentService.createAmendment(permit2.getId(), "迟到变更", List.of(p2Stage));
        var p2Item = permitService.listItemDefinitions(p2Stage).get(0);
        inspectionService.submitInspection("SUB-LATE", p2Item.getId(),
                Conclusion.PASS, "张三", "合格", 1, 1);
        inspectionService.acceptStage(p2Stage);
        approvalService.approve(permit2.getId(), 1);
        return late;
    }

    private void passAndAccept(Long stageId, List<InspectionItemDefinition> items,
                               int planVersion, int stageVersion, String submissionPrefix) {
        int i = 0;
        for (InspectionItemDefinition item : items) {
            inspectionService.submitInspection("SUB-" + submissionPrefix + "-" + (++i), item.getId(),
                    Conclusion.PASS, "张三", "合格", planVersion, stageVersion);
        }
        inspectionService.acceptStage(stageId);
    }

    private void completeAllStages(int planVersion) {
        passAndAccept(stage1Id, stage1Items, planVersion, 1, "F");
        passAndAccept(stage2Id, stage2Items, planVersion, 1, "S");
        passAndAccept(stage3Id, stage3Items, planVersion, 1, "D");
    }
}
