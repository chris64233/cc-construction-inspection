package com.chris64233.constructioninspection;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PlanVersion;
import com.chris64233.constructioninspection.domain.PlanVersionReason;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import com.chris64233.constructioninspection.domain.StageAcceptance;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.VersionReason;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.InspectionRecordRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.repository.PlanVersionRepository;
import com.chris64233.constructioninspection.repository.RectificationRepository;
import com.chris64233.constructioninspection.repository.WorkVersionRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案变更流程测试：部分阶段失效与未受影响结果保留、按新版本复检、
 * 挂起/恢复、整改取消、方案版本与批准依据的完整关联、过期操作拒绝。
 */
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
    PermitRepository permitRepository;
    @Autowired
    ConstructionStageRepository stageRepository;
    @Autowired
    WorkVersionRepository versionRepository;
    @Autowired
    InspectionRecordRepository recordRepository;
    @Autowired
    RectificationRepository rectificationRepository;
    @Autowired
    PlanVersionRepository planVersionRepository;

    Long permitId;
    ConstructionStage s1;
    ConstructionStage s2;
    ConstructionStage s3;
    List<InspectionItemDefinition> s1Items;
    List<InspectionItemDefinition> s2Items;
    List<InspectionItemDefinition> s3Items;

    @BeforeEach
    void setUp() {
        Permit permit = permitService.createPermit("变更测试许可", List.of(
                new PermitService.StageDef("基础工程", List.of(
                        new PermitService.ItemDef("F1", "地基承载力"))),
                new PermitService.StageDef("主体结构", List.of(
                        new PermitService.ItemDef("M1", "混凝土强度"))),
                new PermitService.StageDef("装修工程", List.of(
                        new PermitService.ItemDef("D1", "防水验收")))));
        permitId = permit.getId();
        var stages = permitService.listStages(permitId);
        s1 = stages.get(0);
        s2 = stages.get(1);
        s3 = stages.get(2);
        s1Items = permitService.listItemDefinitions(s1.getId());
        s2Items = permitService.listItemDefinitions(s2.getId());
        s3Items = permitService.listItemDefinitions(s3.getId());
    }

    private void passAndAccept(ConstructionStage stage, List<InspectionItemDefinition> items, String prefix) {
        for (InspectionItemDefinition item : items) {
            inspectionService.submitInspection(prefix + "-" + item.getCode(), item.getId(),
                    Conclusion.PASS, "张三", "合格");
        }
        inspectionService.acceptStage(stage.getId());
        // 重新读取以反映验收后的状态
        var refreshed = stageRepository.findById(stage.getId()).orElseThrow();
        stage.setStatus(refreshed.getStatus());
        stage.setAcceptedVersionId(refreshed.getAcceptedVersionId());
    }

    private void completeAll() {
        passAndAccept(s1, s1Items, "SUB1");
        passAndAccept(s2, s2Items, "SUB2");
        passAndAccept(s3, s3Items, "SUB3");
    }

    private Amendment approveAmendment(List<Long> stageIds) {
        Amendment amendment = amendmentService.propose(permitId, "设计调整", stageIds);
        return amendmentService.approve(amendment.getId(), null).amendment();
    }

    @Test
    void approvalCreatesNewPlanVersion_andOnlyInvalidatesAffectedStageResults() {
        completeAll();
        Long s1RecordId = recordRepository.findValidByStageId(s1.getId()).get(0).getId();
        Long s3RecordId = recordRepository.findValidByStageId(s3.getId()).get(0).getId();

        var result = amendmentService.approve(
                amendmentService.propose(permitId, "主体方案调整", List.of(s2.getId())).getId(), null);

        assertEquals(2, permitRepository.findById(permitId).orElseThrow().getCurrentPlanVersionNumber());
        assertEquals(2, result.planVersion().getVersionNumber());
        assertEquals(PlanVersionReason.AMENDMENT, result.planVersion().getReason());
        // 受影响阶段的处理结果明确可查
        assertEquals(1, result.effects().size());
        var effect = result.effects().get(0);
        assertEquals(s2.getId(), effect.stageId());
        assertEquals(1, effect.invalidatedRecordCount());
        assertEquals(0, effect.cancelledRectificationCount());
        assertEquals(2, effect.newWorkVersionNumber());
        assertTrue(effect.mustReinspect());
        assertEquals("COMPLETED", effect.statusBefore());
        // 前置 S1 未受影响且已完成，S2 立即恢复可检查
        assertEquals("ACTIVE", effect.statusAfter());

        // S2 旧结果失效、新工程版本属于方案 v2
        List<InspectionRecord> s2v1Records = recordRepository.findByVersionId(
                versionRepository.findByStageIdOrderByVersionNumber(s2.getId()).get(0).getId());
        assertEquals(1, s2v1Records.size());
        assertTrue(s2v1Records.get(0).isInvalidated());
        assertEquals(2, s2v1Records.get(0).getInvalidatedByPlanVersion());
        List<WorkVersion> s2Versions = versionRepository.findByStageIdOrderByVersionNumber(s2.getId());
        assertEquals(2, s2Versions.size());
        assertEquals(VersionReason.AMENDMENT, s2Versions.get(1).getReason());
        assertEquals(2, s2Versions.get(1).getPlanVersionNumber());
        assertNull(stageRepository.findById(s2.getId()).orElseThrow().getAcceptedVersionId());

        // 未受影响阶段：有效结果与验收状态全部保留
        assertFalse(recordRepository.findById(s1RecordId).orElseThrow().isInvalidated());
        assertFalse(recordRepository.findById(s3RecordId).orElseThrow().isInvalidated());
        assertEquals(StageStatus.COMPLETED, stageRepository.findById(s1.getId()).orElseThrow().getStatus());
        assertEquals(StageStatus.COMPLETED, stageRepository.findById(s3.getId()).orElseThrow().getStatus());
    }

    @Test
    void affectedStageMustReinspectOnNewVersion_beforeReacceptance() {
        completeAll();
        approveAmendment(List.of(s2.getId()));
        Long s2v2Id = versionRepository.findTopByStageIdOrderByVersionNumberDesc(s2.getId()).orElseThrow().getId();

        // 新版本尚未复检：验收被拒
        BusinessException ex = assertThrows(BusinessException.class,
                () -> inspectionService.acceptStage(s2.getId()));
        assertTrue(ex.getMessage().contains("尚未检查"));

        // 携带旧工程版本提交：过期操作被拒
        Long s2v1Id = versionRepository.findByStageIdOrderByVersionNumber(s2.getId()).get(0).getId();
        BusinessException stale = assertThrows(BusinessException.class, () ->
                inspectionService.submitInspection("STALE-S2", s2Items.get(0).getId(),
                        Conclusion.PASS, "王五", "按旧版本", s2v1Id));
        assertTrue(stale.getMessage().contains("过期"));

        // 按新版本复检通过后才能重新验收
        inspectionService.submitInspection("NEW-M1", s2Items.get(0).getId(),
                Conclusion.PASS, "李四", "按新方案复检", s2v2Id);
        var reaccepted = inspectionService.acceptStage(s2.getId());
        assertEquals(StageStatus.COMPLETED, reaccepted.getStatus());
        assertEquals(s2v2Id, reaccepted.getAcceptedVersionId());

        // 每次验收依据完整关联：S2 有两条验收记录，分别属于方案 v1 / v2
        List<StageAcceptance> history = inspectionService.listAcceptanceHistory(s2.getId());
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).getPlanVersion().getVersionNumber());
        assertEquals(2, history.get(1).getPlanVersion().getVersionNumber());
        assertEquals(s2v2Id, history.get(1).getAcceptedVersion().getId());
    }

    @Test
    void amendmentOnFirstAndSecondStage_suspendsUntilPredecessorReaccepted() {
        completeAll();
        // 同时影响 S1、S2：S1 无前置可立即恢复，S2 因前置 S1 待复检而挂起
        var result = amendmentService.approve(amendmentService.propose(
                permitId, "基础与主体调整", List.of(s1.getId(), s2.getId())).getId(), null);
        assertEquals("ACTIVE", result.effects().get(0).statusAfter());
        assertEquals("SUSPENDED", result.effects().get(1).statusAfter());

        // 挂起阶段不可提交检查
        BusinessException ex = assertThrows(BusinessException.class, () ->
                inspectionService.submitInspection("S2-WHILE-SUSPENDED", s2Items.get(0).getId(),
                        Conclusion.PASS, "张三", "尝试检查"));
        assertTrue(ex.getMessage().contains("挂起"));

        // S1 按新方案复检并验收后，S2 自动恢复（不重复生成版本）
        Long s1v2 = versionRepository.findTopByStageIdOrderByVersionNumberDesc(s1.getId()).orElseThrow().getId();
        inspectionService.submitInspection("NEW-F1", s1Items.get(0).getId(),
                Conclusion.PASS, "李四", "新方案", s1v2);
        inspectionService.acceptStage(s1.getId());
        assertEquals(StageStatus.ACTIVE, stageRepository.findById(s2.getId()).orElseThrow().getStatus());
        // S2 仍只有变更时产生的那一个新版本，未因恢复再生成版本
        assertEquals(2, versionRepository.findByStageIdOrderByVersionNumber(s2.getId()).size());

        Long s2v2 = versionRepository.findTopByStageIdOrderByVersionNumberDesc(s2.getId()).orElseThrow().getId();
        inspectionService.submitInspection("NEW-M1", s2Items.get(0).getId(),
                Conclusion.PASS, "李四", "新方案", s2v2);
        inspectionService.acceptStage(s2.getId());
        // S3 未受影响，仍是 COMPLETED，全部阶段可最终批准
        var approval = approvalService.approve(permitId, 2);
        assertTrue(approval.getBasis().contains("方案 v2"));
    }

    @Test
    void pendingAffectedStage_delaysVersionUntilActivated_underNewPlan() {
        // 仅完成 S1；S2 激活中，S3 仍 PENDING（尚无工程版本）
        passAndAccept(s1, s1Items, "SUB1");
        var result = amendmentService.approve(
                amendmentService.propose(permitId, "装修方案提前调整", List.of(s3.getId())).getId(), null);

        assertNull(result.effects().get(0).newWorkVersionNumber());
        assertFalse(result.effects().get(0).mustReinspect());
        assertEquals(StageStatus.PENDING, stageRepository.findById(s3.getId()).orElseThrow().getStatus());
        assertTrue(versionRepository.findByStageIdOrderByVersionNumber(s3.getId()).isEmpty());

        // 完成 S2（其 v1 属方案 v1，结果保留有效），激活 S3：初始版本按当前方案 v2 生成
        passAndAccept(s2, s2Items, "SUB2");
        WorkVersion s3v1 = versionRepository.findTopByStageIdOrderByVersionNumberDesc(s3.getId()).orElseThrow();
        assertEquals(1, s3v1.getVersionNumber());
        assertEquals(2, s3v1.getPlanVersionNumber());
        assertEquals(VersionReason.INITIAL, s3v1.getReason());
        assertEquals(StageStatus.ACTIVE, stageRepository.findById(s3.getId()).orElseThrow().getStatus());

        passAndAccept(s3, s3Items, "SUB3");
        var approval = approvalService.approve(permitId, 2);
        // S3 依据为方案 v2 下的工程版本 v1
        assertTrue(approval.getBasis().contains("装修工程(方案 v2、工程版本 v1)"));
    }

    @Test
    void openRectificationsAreCancelled_closedChainKeptAsHistory() {
        inspectionService.submitInspection("FAIL-F1", s1Items.get(0).getId(),
                Conclusion.FAIL, "张三", "承载力不足");
        Rectification open = inspectionService.listRectificationChain(s1.getId()).get(0);

        approveAmendment(List.of(s1.getId()));

        Rectification cancelled = rectificationRepository.findById(open.getId()).orElseThrow();
        assertEquals(RectificationStatus.CANCELLED, cancelled.getStatus());
        assertEquals(2, cancelled.getCancelledByPlanVersion());
        // 已取消整改不可再提交
        assertThrows(BusinessException.class,
                () -> inspectionService.submitRectification(open.getId(), "补整改"));
        // 无未关闭整改，按新版本复检通过即可验收（不再被旧整改阻塞）
        Long s1v2 = versionRepository.findTopByStageIdOrderByVersionNumberDesc(s1.getId()).orElseThrow().getId();
        inspectionService.submitInspection("NEW-F1", s1Items.get(0).getId(),
                Conclusion.PASS, "李四", "合格", s1v2);
        assertEquals(StageStatus.COMPLETED, inspectionService.acceptStage(s1.getId()).getStatus());
    }

    @Test
    void finalApprovalRejectsStalePlanVersion_andRequiresReinspection() {
        completeAll();
        approveAmendment(List.of(s2.getId()));

        // 受影响阶段未复检完成：最终批准被阻止
        BusinessException unfinished = assertThrows(BusinessException.class,
                () -> approvalService.approve(permitId, null));
        assertTrue(unfinished.getMessage().contains("未完成阶段"));
        // 携带过期方案版本号：明确拒绝过期操作
        BusinessException stale = assertThrows(BusinessException.class,
                () -> approvalService.approve(permitId, 1));
        assertTrue(stale.getMessage().contains("当前方案已演进至 v2"));

        // 复检并重新验收后，按当前方案 v2 批准成功
        Long s2v2 = versionRepository.findTopByStageIdOrderByVersionNumberDesc(s2.getId()).orElseThrow().getId();
        inspectionService.submitInspection("NEW-M1", s2Items.get(0).getId(),
                Conclusion.PASS, "李四", "新方案", s2v2);
        inspectionService.acceptStage(s2.getId());
        var approval = approvalService.approve(permitId, 2);
        assertEquals(2, approval.getPlanVersionNumber());
        var basis = approvalService.getApprovalBasis(permitId);
        // S1/S3 保留方案 v1 验收，S2 经历"v1 验收 → 失效打回 → v2 重新验收"，历史共两条
        assertEquals(1, basis.stages().get(0).planVersionNumber());
        assertEquals(2, basis.stages().get(1).planVersionNumber());
        assertEquals(2, basis.stages().get(1).acceptanceCount());
        assertEquals(2, basis.stages().get(1).acceptedVersionNumber());
    }

    @Test
    void planVersionHistoryLinksBackToAmendment() {
        Amendment amendment = approveAmendment(List.of(s1.getId()));

        List<PlanVersion> versions = amendmentService.listPlanVersions(permitId);
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).getVersionNumber());
        assertEquals(PlanVersionReason.INITIAL, versions.get(0).getReason());
        assertNull(versions.get(0).getAmendment());
        assertEquals(2, versions.get(1).getVersionNumber());
        assertEquals(amendment.getId(), versions.get(1).getAmendment().getId());

        Amendment viewed = amendmentService.getAmendment(amendment.getId());
        assertEquals("APPROVED", viewed.getStatus().name());
        assertEquals(2, viewed.getResultPlanVersionNumber());
        assertEquals(1, viewed.getBasePlanVersionNumber());
        assertEquals(List.of(s1.getId()),
                amendmentService.listAffectedStages(amendment.getId()).stream()
                        .map(ConstructionStage::getId).toList());
    }

    @Test
    void amendmentGuards() {
        // 受影响阶段不能为空 / 不能重复 / 必须属于本许可
        assertThrows(BusinessException.class, () ->
                amendmentService.propose(permitId, "空", List.of()));
        assertThrows(BusinessException.class, () ->
                amendmentService.propose(permitId, "重复", List.of(s1.getId(), s1.getId())));
        assertThrows(BusinessException.class, () ->
                amendmentService.propose(permitId, "跨许可", List.of(999999L)));

        // 同一许可只允许一个待批准变更
        amendmentService.propose(permitId, "待批1", List.of(s1.getId()));
        assertThrows(BusinessException.class, () ->
                amendmentService.propose(permitId, "待批2", List.of(s2.getId())));

        // 最终批准后不可再变更
        completeAll();
        // 清理待批变更后完成流程（直接批准它即可推进）
        Amendment pending = amendmentService.listAmendments(permitId).stream()
                .filter(a -> a.getStatus().name().equals("PROPOSED")).findFirst().orElseThrow();
        // 该待批变更影响 S1：批准后需复检 S1 才能最终批准
        amendmentService.approve(pending.getId(), null);
        Long s1v2 = versionRepository.findTopByStageIdOrderByVersionNumberDesc(s1.getId()).orElseThrow().getId();
        inspectionService.submitInspection("RE-F1", s1Items.get(0).getId(),
                Conclusion.PASS, "李四", "新方案", s1v2);
        inspectionService.acceptStage(s1.getId());
        approvalService.approve(permitId, 2);

        assertThrows(BusinessException.class, () ->
                amendmentService.propose(permitId, "事后变更", List.of(s1.getId())));
    }

    @Test
    void duplicateAmendmentApprovalRejected_andStaleBaseVersionRejected() {
        Amendment amendment = amendmentService.propose(permitId, "变更", List.of(s1.getId()));
        amendmentService.approve(amendment.getId(), 1);
        // 重复批准
        assertThrows(BusinessException.class, () -> amendmentService.approve(amendment.getId(), 1));
        // 携带过期基准版本号批准
        Amendment second = amendmentService.propose(permitId, "再次变更", List.of(s1.getId()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> amendmentService.approve(second.getId(), 1));
        assertTrue(ex.getMessage().contains("过期"));
    }
}
