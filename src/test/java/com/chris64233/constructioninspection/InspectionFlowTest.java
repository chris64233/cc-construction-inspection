package com.chris64233.constructioninspection;

import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.FinalApproval;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import com.chris64233.constructioninspection.domain.Stage;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.StopWorkOrder;
import com.chris64233.constructioninspection.repo.InspectionRecordRepository;
import com.chris64233.constructioninspection.service.ApprovalService;
import com.chris64233.constructioninspection.service.BusinessException;
import com.chris64233.constructioninspection.service.InspectionService;
import com.chris64233.constructioninspection.service.PermitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class InspectionFlowTest {

    @Autowired
    PermitService permitService;
    @Autowired
    InspectionService inspectionService;
    @Autowired
    ApprovalService approvalService;
    @Autowired
    InspectionRecordRepository inspectionRecordRepository;

    private static final AtomicInteger PERMIT_SEQ = new AtomicInteger();

    /** 两阶段许可：阶段1检查项 A/B，阶段2检查项 C */
    private Permit newPermit() {
        int n = PERMIT_SEQ.incrementAndGet();
        return permitService.createPermit("P-" + n, "项目-" + n, List.of(
                new PermitService.StageDefinition(1, "地基与基础", List.of(
                        new PermitService.ItemDefinition("A", "基槽验收"),
                        new PermitService.ItemDefinition("B", "钢筋隐蔽"))),
                new PermitService.StageDefinition(2, "主体结构", List.of(
                        new PermitService.ItemDefinition("C", "模板安装")))));
    }

    private InspectionService.InspectionResult pass(Long permitId, int seq, String item, String subNo) {
        return inspectionService.submitInspection(permitId, seq, item, Conclusion.PASS,
                "检查员甲", "证据-" + subNo, subNo);
    }

    private InspectionService.InspectionResult fail(Long permitId, int seq, String item, String subNo) {
        return inspectionService.submitInspection(permitId, seq, item, Conclusion.FAIL,
                "检查员甲", "证据-" + subNo, subNo);
    }

    @Test
    void cannotInspectLaterStageBeforePreviousAccepted() {
        Permit permit = newPermit();
        assertThatThrownBy(() -> pass(permit.getId(), 2, "C", "s1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("前置阶段");
    }

    @Test
    void failedInspectionCreatesRectificationAndBlocksAcceptance() {
        Permit permit = newPermit();
        pass(permit.getId(), 1, "A", "s1");
        InspectionService.InspectionResult failed = fail(permit.getId(), 1, "B", "s2");

        assertThat(failed.rectification()).isNotNull();
        assertThat(failed.rectification().getStatus()).isEqualTo(RectificationStatus.OPEN);
        assertThat(failed.rectification().getRaisedVersion()).isEqualTo(1);

        assertThatThrownBy(() -> inspectionService.acceptStage(permit.getId(), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("整改项");
    }

    @Test
    void rectificationSubmissionBumpsVersionAndReinspectionClosesIt() {
        Permit permit = newPermit();
        pass(permit.getId(), 1, "A", "s1");
        Rectification rect = fail(permit.getId(), 1, "B", "s2").rectification();

        Rectification submitted = inspectionService.submitRectification(permit.getId(), 1, rect.getId(), "r1");
        assertThat(submitted.getStatus()).isEqualTo(RectificationStatus.SUBMITTED);
        Stage stage = permitService.listStages(permit.getId()).get(0);
        assertThat(stage.getCurrentVersion()).isEqualTo(2);

        // 复检通过 → 整改关闭
        pass(permit.getId(), 1, "B", "s3");
        List<Rectification> chain = inspectionService.listRectifications(permit.getId(), 1);
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).getStatus()).isEqualTo(RectificationStatus.CLOSED);
        assertThat(chain.get(0).getClosedVersion()).isEqualTo(2);

        // 版本已升到 2，A 在版本 1 的通过已过期，验收必须基于当前版本
        assertThatThrownBy(() -> inspectionService.acceptStage(permit.getId(), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前版本 2");

        pass(permit.getId(), 1, "A", "s4");
        Stage accepted = inspectionService.acceptStage(permit.getId(), 1);
        assertThat(accepted.getStatus()).isEqualTo(StageStatus.ACCEPTED);
        assertThat(accepted.getAcceptedAt()).isNotNull();
    }

    @Test
    void acceptanceRequiresAllItemsPassedOnCurrentVersion() {
        Permit permit = newPermit();
        pass(permit.getId(), 1, "A", "s1");
        assertThatThrownBy(() -> inspectionService.acceptStage(permit.getId(), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("B");
    }

    @Test
    void inspectionSubmissionNoIsIdempotent() {
        Permit permit = newPermit();
        InspectionRecord first = pass(permit.getId(), 1, "A", "sub-1").record();
        InspectionService.InspectionResult replay = pass(permit.getId(), 1, "A", "sub-1");

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.record().getId()).isEqualTo(first.getId());
        assertThat(inspectionRecordRepository.findByStageIdOrderByVersionAscItemCodeAsc(
                first.getStage().getId())).hasSize(1);
    }

    @Test
    void rectificationSubmissionIsIdempotent() {
        Permit permit = newPermit();
        Rectification rect = fail(permit.getId(), 1, "A", "s1").rectification();

        Rectification first = inspectionService.submitRectification(permit.getId(), 1, rect.getId(), "r1");
        Rectification replay = inspectionService.submitRectification(permit.getId(), 1, rect.getId(), "r1");
        assertThat(replay.getStatus()).isEqualTo(RectificationStatus.SUBMITTED);

        Stage stage = permitService.listStages(permit.getId()).get(0);
        assertThat(stage.getCurrentVersion()).isEqualTo(2); // 版本只提升一次

        assertThatThrownBy(() -> inspectionService.submitRectification(permit.getId(), 1, first.getId(), "r2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已提交");
    }

    @Test
    void onlyOneEffectiveConclusionPerItemPerVersion() {
        Permit permit = newPermit();
        pass(permit.getId(), 1, "A", "s1");
        assertThatThrownBy(() -> fail(permit.getId(), 1, "A", "s2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("生效结论");
    }

    @Test
    void fullFlowToFinalApproval() {
        Permit permit = newPermit();
        pass(permit.getId(), 1, "A", "s1");
        pass(permit.getId(), 1, "B", "s2");
        inspectionService.acceptStage(permit.getId(), 1);
        pass(permit.getId(), 2, "C", "s3");
        inspectionService.acceptStage(permit.getId(), 2);

        FinalApproval approval = approvalService.approveFinal(permit.getId(), "审批人甲");
        assertThat(approval.getApprovedAt()).isNotNull();

        // 重复批准幂等，返回同一记录
        FinalApproval replay = approvalService.approveFinal(permit.getId(), "审批人乙");
        assertThat(replay.getId()).isEqualTo(approval.getId());

        // 已批准记录不可修改：不能再签发停工令
        assertThatThrownBy(() -> approvalService.issueStopOrder(permit.getId(), "违规", "监督员"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已获最终批准");

        // 批准依据查询
        ApprovalService.ApprovalBasis basis = approvalService.getApprovalBasis(permit.getId());
        assertThat(basis.approval().getId()).isEqualTo(approval.getId());
        assertThat(basis.stages()).hasSize(2)
                .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(StageStatus.ACCEPTED));
    }

    @Test
    void finalApprovalBlockedByActiveStopOrder() {
        Permit permit = newPermit();
        pass(permit.getId(), 1, "A", "s1");
        pass(permit.getId(), 1, "B", "s2");
        inspectionService.acceptStage(permit.getId(), 1);
        pass(permit.getId(), 2, "C", "s3");
        inspectionService.acceptStage(permit.getId(), 2);

        StopWorkOrder order = approvalService.issueStopOrder(permit.getId(), "安全隐患", "监督员");
        assertThatThrownBy(() -> approvalService.approveFinal(permit.getId(), "审批人甲"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("活动停工令");

        approvalService.liftStopOrder(permit.getId(), order.getId());
        FinalApproval approval = approvalService.approveFinal(permit.getId(), "审批人甲");
        assertThat(approval).isNotNull();

        ApprovalService.ApprovalBasis basis = approvalService.getApprovalBasis(permit.getId());
        assertThat(basis.stopOrders()).hasSize(1);
        assertThat(basis.stopOrders().get(0).getLiftedAt()).isNotNull();
    }

    @Test
    void concurrentApprovalAndStopOrderYieldExactlyOneOutcome() throws Exception {
        Permit permit = newPermit();
        pass(permit.getId(), 1, "A", "s1");
        pass(permit.getId(), 1, "B", "s2");
        inspectionService.acceptStage(permit.getId(), 1);
        pass(permit.getId(), 2, "C", "s3");
        inspectionService.acceptStage(permit.getId(), 2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Boolean> approve = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    approvalService.approveFinal(permit.getId(), "审批人甲");
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            });
            Future<Boolean> stopOrder = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    approvalService.issueStopOrder(permit.getId(), "安全隐患", "监督员");
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            boolean approved = approve.get(30, TimeUnit.SECONDS);
            boolean stopped = stopOrder.get(30, TimeUnit.SECONDS);
            // 只能形成批准或被阻止其中一种结果
            assertThat(approved).isNotEqualTo(stopped);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReinspectionKeepsSingleEffectiveConclusion() throws Exception {
        Permit permit = newPermit();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                go.await();
                try {
                    inspectionService.submitInspection(permit.getId(), 1, "A", Conclusion.PASS,
                            "检查员甲", "证据", "sub-" + Thread.currentThread().threadId());
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            };
            Future<Boolean> f1 = pool.submit(task);
            Future<Boolean> f2 = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            assertThat(f1.get(30, TimeUnit.SECONDS)).isNotEqualTo(f2.get(30, TimeUnit.SECONDS));
            Stage stage = permitService.listStages(permit.getId()).get(0);
            assertThat(inspectionRecordRepository.findByStageIdOrderByVersionAscItemCodeAsc(stage.getId()))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void acceptanceCannotUseStaleResultsWhenRectificationBumpsVersion() {
        Permit permit = newPermit();
        pass(permit.getId(), 1, "A", "s1");
        Rectification rect = fail(permit.getId(), 1, "B", "s2").rectification();

        // 整改提交把版本升到 2 之后，版本 1 的通过记录全部过期
        inspectionService.submitRectification(permit.getId(), 1, rect.getId(), "r1");
        // 复检关闭整改项后，A 在版本 1 的通过仍然不能作为验收依据
        pass(permit.getId(), 1, "B", "s3");
        assertThatThrownBy(() -> inspectionService.acceptStage(permit.getId(), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前版本 2");
    }
}
