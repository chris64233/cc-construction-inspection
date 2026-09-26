package com.chris64233.constructioninspection;

import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.VersionReason;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.InspectionRecordRepository;
import com.chris64233.constructioninspection.repository.WorkVersionRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class InspectionWorkflowTest {

    @Autowired
    PermitService permitService;
    @Autowired
    InspectionService inspectionService;
    @Autowired
    ApprovalService approvalService;
    @Autowired
    ConstructionStageRepository stageRepository;
    @Autowired
    WorkVersionRepository versionRepository;
    @Autowired
    InspectionRecordRepository recordRepository;

    Long permitId;
    Long stage1Id;
    Long stage2Id;
    List<InspectionItemDefinition> stage1Items;
    List<InspectionItemDefinition> stage2Items;

    @BeforeEach
    void setUp() {
        Permit permit = permitService.createPermit("测试许可", List.of(
                new PermitService.StageDef("基础工程", List.of(
                        new PermitService.ItemDef("F1", "地基承载力"),
                        new PermitService.ItemDef("F2", "钢筋绑扎"))),
                new PermitService.StageDef("主体结构", List.of(
                        new PermitService.ItemDef("S1", "混凝土强度")))));
        permitId = permit.getId();
        var stages = permitService.listStages(permitId);
        stage1Id = stages.get(0).getId();
        stage2Id = stages.get(1).getId();
        stage1Items = permitService.listItemDefinitions(stage1Id);
        stage2Items = permitService.listItemDefinitions(stage2Id);
    }

    @Test
    void createPermit_activatesFirstStageWithInitialVersionOnly() {
        var stages = permitService.listStages(permitId);
        assertEquals(StageStatus.ACTIVE, stages.get(0).getStatus());
        assertEquals(StageStatus.PENDING, stages.get(1).getStatus());
        var versions = inspectionService.listVersions(stage1Id);
        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0).getVersionNumber());
        assertEquals(VersionReason.INITIAL, versions.get(0).getReason());
        assertTrue(inspectionService.listVersions(stage2Id).isEmpty());
    }

    @Test
    void priorStageIncomplete_blocksLaterStageInspection() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                inspectionService.submitInspection("SUB-X1", stage2Items.get(0).getId(),
                        Conclusion.PASS, "张三", "现场照片"));
        assertTrue(ex.getMessage().contains("前置阶段未完成"));
    }

    @Test
    void failedInspection_generatesRectification_andBlocksAcceptance() {
        inspectionService.submitInspection("SUB-1", stage1Items.get(0).getId(),
                Conclusion.PASS, "张三", "合格");
        inspectionService.submitInspection("SUB-2", stage1Items.get(1).getId(),
                Conclusion.FAIL, "张三", "钢筋间距超标");

        List<Rectification> chain = inspectionService.listRectificationChain(stage1Id);
        assertEquals(1, chain.size());
        assertEquals(RectificationStatus.OPEN, chain.get(0).getStatus());
        assertEquals("F2", chain.get(0).getSourceRecord().getItemDefinition().getCode());

        assertThrows(BusinessException.class, () -> inspectionService.acceptStage(stage1Id));
    }

    @Test
    void rectificationSubmission_closesItemAndCreatesNewReinspectionVersion() {
        inspectionService.submitInspection("SUB-1", stage1Items.get(0).getId(),
                Conclusion.FAIL, "张三", "地基承载力不足");
        Rectification rectification = inspectionService.listRectificationChain(stage1Id).get(0);

        Rectification closed = inspectionService.submitRectification(rectification.getId(), "已换填处理");

        assertEquals(RectificationStatus.CLOSED, closed.getStatus());
        assertNotNull(closed.getClosedAt());
        assertEquals(2, closed.getResultVersion().getVersionNumber());

        List<WorkVersion> versions = inspectionService.listVersions(stage1Id);
        assertEquals(2, versions.size());
        assertEquals(VersionReason.RECTIFICATION, versions.get(1).getReason());

        // 重复提交整改被拒绝
        assertThrows(BusinessException.class,
                () -> inspectionService.submitRectification(rectification.getId(), "再次提交"));
    }

    @Test
    void acceptanceRequiresAllItemsPassedOnCurrentVersion_andAllRectificationsClosed() {
        // v1：F1 通过，F2 不通过 → 整改 → 产生 v2
        inspectionService.submitInspection("SUB-1", stage1Items.get(0).getId(),
                Conclusion.PASS, "张三", "合格");
        inspectionService.submitInspection("SUB-2", stage1Items.get(1).getId(),
                Conclusion.FAIL, "张三", "不合格");
        Rectification rectification = inspectionService.listRectificationChain(stage1Id).get(0);
        inspectionService.submitRectification(rectification.getId(), "已整改");

        // v2 上尚无检查记录：验收不得基于 v1 的过期结果
        BusinessException ex = assertThrows(BusinessException.class,
                () -> inspectionService.acceptStage(stage1Id));
        assertTrue(ex.getMessage().contains("尚未检查"));

        // v2 上重新检查全部通过后才能验收
        inspectionService.submitInspection("SUB-3", stage1Items.get(0).getId(),
                Conclusion.PASS, "李四", "复检合格");
        inspectionService.submitInspection("SUB-4", stage1Items.get(1).getId(),
                Conclusion.PASS, "李四", "复检合格");
        var stage = inspectionService.acceptStage(stage1Id);

        assertEquals(StageStatus.COMPLETED, stage.getStatus());
        Long v2Id = inspectionService.listVersions(stage1Id).get(1).getId();
        assertEquals(v2Id, stage.getAcceptedVersionId());

        // 下一阶段被激活并生成初始版本
        var stage2 = stageRepository.findById(stage2Id).orElseThrow();
        assertEquals(StageStatus.ACTIVE, stage2.getStatus());
        assertEquals(1, inspectionService.listVersions(stage2Id).size());
    }

    @Test
    void acceptanceBlockedByOpenRectification_evenWhenCurrentVersionAllPass() {
        // 两个检查项均不通过 → 两个整改项
        inspectionService.submitInspection("SUB-1", stage1Items.get(0).getId(),
                Conclusion.FAIL, "张三", "问题1");
        inspectionService.submitInspection("SUB-2", stage1Items.get(1).getId(),
                Conclusion.FAIL, "张三", "问题2");
        List<Rectification> chain = inspectionService.listRectificationChain(stage1Id);
        assertEquals(2, chain.size());

        // 只关闭第一个整改项（产生 v2），第二个仍打开
        inspectionService.submitRectification(chain.get(0).getId(), "已整改");
        inspectionService.submitInspection("SUB-3", stage1Items.get(0).getId(),
                Conclusion.PASS, "李四", "复检合格");
        inspectionService.submitInspection("SUB-4", stage1Items.get(1).getId(),
                Conclusion.PASS, "李四", "复检合格");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inspectionService.acceptStage(stage1Id));
        assertTrue(ex.getMessage().contains("整改项"));
    }

    @Test
    void submissionNoIsIdempotent() {
        InspectionRecord first = inspectionService.submitInspection("SUB-IDEMP",
                stage1Items.get(0).getId(), Conclusion.PASS, "张三", "合格");
        InspectionRecord second = inspectionService.submitInspection("SUB-IDEMP",
                stage1Items.get(0).getId(), Conclusion.PASS, "张三", "合格");

        assertEquals(first.getId(), second.getId());
        Long v1Id = inspectionService.listVersions(stage1Id).get(0).getId();
        assertEquals(1, recordRepository.findByVersionId(v1Id).size());
    }

    @Test
    void oneEffectiveConclusionPerItemPerVersion() {
        inspectionService.submitInspection("SUB-A", stage1Items.get(0).getId(),
                Conclusion.PASS, "张三", "合格");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                inspectionService.submitInspection("SUB-B", stage1Items.get(0).getId(),
                        Conclusion.FAIL, "李四", "改判不合格"));
        assertTrue(ex.getMessage().contains("已存在生效结论"));
    }

    @Test
    void finalApprovalRequiresAllStagesCompleted() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> approvalService.approve(permitId));
        assertTrue(ex.getMessage().contains("未完成阶段"));
    }

    @Test
    void finalApprovalBlockedByActiveStopWorkOrder_thenSucceedsAfterLift() {
        completeAllStages();

        var order = approvalService.issueStopWorkOrder(permitId, "安全隐患");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> approvalService.approve(permitId));
        assertTrue(ex.getMessage().contains("活动停工令"));

        approvalService.liftStopWorkOrder(order.getId());
        var approval = approvalService.approve(permitId);
        assertNotNull(approval.getApprovedAt());
        assertTrue(approval.getBasis().contains("基础工程"));
        assertTrue(approval.getBasis().contains("主体结构"));
    }

    @Test
    void approvedRecordIsImmutable_noReapproveNoStopWorkOrder() {
        completeAllStages();
        approvalService.approve(permitId);

        assertThrows(BusinessException.class, () -> approvalService.approve(permitId));
        assertThrows(BusinessException.class,
                () -> approvalService.issueStopWorkOrder(permitId, "事后停工"));
    }

    @Test
    void approvalBasisQueryReturnsStagesAndAcceptedVersions() {
        completeAllStages();
        approvalService.approve(permitId);

        var basis = approvalService.getApprovalBasis(permitId);
        assertEquals(2, basis.stages().size());
        assertEquals("基础工程", basis.stages().get(0).name());
        assertEquals(1, basis.stages().get(0).acceptedVersionNumber());
        assertEquals("COMPLETED", basis.stages().get(1).status());
        assertTrue(basis.stopWorkOrders().isEmpty());
    }

    private void completeAllStages() {
        inspectionService.submitInspection("SUB-F1", stage1Items.get(0).getId(),
                Conclusion.PASS, "张三", "合格");
        inspectionService.submitInspection("SUB-F2", stage1Items.get(1).getId(),
                Conclusion.PASS, "张三", "合格");
        inspectionService.acceptStage(stage1Id);
        inspectionService.submitInspection("SUB-S1", stage2Items.get(0).getId(),
                Conclusion.PASS, "张三", "合格");
        inspectionService.acceptStage(stage2Id);
    }
}
