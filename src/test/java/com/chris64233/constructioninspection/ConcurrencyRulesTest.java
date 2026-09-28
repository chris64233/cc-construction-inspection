package com.chris64233.constructioninspection;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.domain.AmendmentStatus;
import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.StopWorkOrderStatus;
import com.chris64233.constructioninspection.domain.VersionReason;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.AmendmentRepository;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.FinalApprovalRepository;
import com.chris64233.constructioninspection.repository.InspectionItemDefinitionRepository;
import com.chris64233.constructioninspection.repository.InspectionRecordRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.repository.PlanVersionRepository;
import com.chris64233.constructioninspection.repository.RectificationRepository;
import com.chris64233.constructioninspection.repository.StopWorkOrderRepository;
import com.chris64233.constructioninspection.repository.WorkVersionRepository;
import com.chris64233.constructioninspection.service.AmendmentService;
import com.chris64233.constructioninspection.service.ApprovalService;
import com.chris64233.constructioninspection.service.InspectionService;
import com.chris64233.constructioninspection.service.PermitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发规则测试：最终批准与停工令签发互斥；阶段验收不会基于过期结果。
 * 不使用测试级事务，每个服务调用在各自事务中执行。
 */
@SpringBootTest
class ConcurrencyRulesTest {

    @Autowired
    PermitService permitService;
    @Autowired
    InspectionService inspectionService;
    @Autowired
    ApprovalService approvalService;
    @Autowired
    PermitRepository permitRepository;
    @Autowired
    ConstructionStageRepository stageRepository;
    @Autowired
    InspectionItemDefinitionRepository itemDefinitionRepository;
    @Autowired
    WorkVersionRepository versionRepository;
    @Autowired
    InspectionRecordRepository recordRepository;
    @Autowired
    RectificationRepository rectificationRepository;
    @Autowired
    StopWorkOrderRepository stopWorkOrderRepository;
    @Autowired
    FinalApprovalRepository finalApprovalRepository;
    @Autowired
    AmendmentService amendmentService;
    @Autowired
    AmendmentRepository amendmentRepository;
    @Autowired
    PlanVersionRepository planVersionRepository;

    @BeforeEach
    @AfterEach
    void cleanup() {
        rectificationRepository.deleteAll();
        recordRepository.deleteAll();
        versionRepository.deleteAll();
        itemDefinitionRepository.deleteAll();
        stageRepository.deleteAll();
        planVersionRepository.deleteAll();
        amendmentRepository.deleteAll();
        stopWorkOrderRepository.deleteAll();
        finalApprovalRepository.deleteAll();
        permitRepository.deleteAll();
    }

    @Test
    void approvalAndStopWorkOrderRace_exactlyOneOutcome() throws Exception {
        // 单阶段许可，全部检查通过并验收完成
        Permit permit = permitService.createPermit("并发许可", List.of(
                new PermitService.StageDef("唯一阶段", List.of(
                        new PermitService.ItemDef("A1", "检查项A1")))));
        Long stageId = stageRepository.findByPermitIdOrderBySeq(permit.getId()).get(0).getId();
        InspectionItemDefinition item = itemDefinitionRepository.findByStageIdOrderById(stageId).get(0);
        inspectionService.submitInspection("SUB-C1", item.getId(), Conclusion.PASS, "张三", "合格", 1, 1);
        inspectionService.acceptStage(stageId);

        // 并发：最终批准 vs 签发停工令
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> approve = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> approvalService.approve(permit.getId(), 1));
        });
        Future<?> stopOrder = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> approvalService.issueStopWorkOrder(permit.getId(), "并发停工令"));
        });
        ready.await();
        start.countDown();
        approve.get(30, TimeUnit.SECONDS);
        stopOrder.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        boolean approved = finalApprovalRepository.findByPermitId(permit.getId()).isPresent();
        boolean activeStopOrder = stopWorkOrderRepository
                .countByPermitIdAndStatus(permit.getId(), StopWorkOrderStatus.ACTIVE) > 0;
        // 只能形成"已批准"或"被停工令阻止"其中一种结果
        assertTrue(approved ^ activeStopOrder,
                "approved=" + approved + ", activeStopOrder=" + activeStopOrder);
    }

    @Test
    void acceptanceAndConcurrentFailInspection_neverStaleAcceptance() throws Exception {
        // 两个检查项：A1 已通过；并发执行"验收"与"A2 不通过"
        Permit permit = permitService.createPermit("并发验收许可", List.of(
                new PermitService.StageDef("阶段一", List.of(
                        new PermitService.ItemDef("A1", "检查项A1"),
                        new PermitService.ItemDef("A2", "检查项A2")))));
        Long stageId = stageRepository.findByPermitIdOrderBySeq(permit.getId()).get(0).getId();
        List<InspectionItemDefinition> items = itemDefinitionRepository.findByStageIdOrderById(stageId);
        inspectionService.submitInspection("SUB-P1", items.get(0).getId(), Conclusion.PASS, "张三", "合格", 1, 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> accept = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> inspectionService.acceptStage(stageId));
        });
        Future<?> failInspection = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> inspectionService.submitInspection(
                    "SUB-FAIL", items.get(1).getId(), Conclusion.FAIL, "李四", "不合格", 1, 1));
        });
        ready.await();
        start.countDown();
        accept.get(30, TimeUnit.SECONDS);
        failInspection.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        boolean stageCompleted = stageRepository.findById(stageId).orElseThrow()
                .getStatus() == StageStatus.COMPLETED;
        Long v1Id = versionRepository.findTopByStageIdOrderByVersionNumberDesc(stageId).orElseThrow().getId();
        boolean failRecorded = recordRepository.findByVersionId(v1Id).stream()
                .anyMatch(r -> r.getConclusion() == Conclusion.FAIL);
        // 若验收通过则不通过结论必然未生效；若不通过结论已生效则验收必然失败
        assertTrue(stageCompleted ^ failRecorded,
                "stageCompleted=" + stageCompleted + ", failRecorded=" + failRecorded);
    }

    @Test
    void concurrentDuplicateSubmissionNo_resultsInSingleRecord() throws Exception {
        Permit permit = permitService.createPermit("幂等许可", List.of(
                new PermitService.StageDef("阶段一", List.of(
                        new PermitService.ItemDef("A1", "检查项A1")))));
        Long stageId = stageRepository.findByPermitIdOrderBySeq(permit.getId()).get(0).getId();
        Long itemId = itemDefinitionRepository.findByStageIdOrderById(stageId).get(0).getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable submit = () -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> inspectionService.submitInspection(
                    "SUB-DUP", itemId, Conclusion.PASS, "张三", "合格", 1, 1));
        };
        Future<?> f1 = pool.submit(submit);
        Future<?> f2 = pool.submit(submit);
        ready.await();
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 无论并发结果如何，同一提交号只会留下一条生效记录
        Long v1Id = versionRepository.findTopByStageIdOrderByVersionNumberDesc(stageId).orElseThrow().getId();
        assertEquals(1, recordRepository.findByVersionId(v1Id).size());
        assertTrue(recordRepository.findBySubmissionNo("SUB-DUP").isPresent());
    }

    @Test
    void amendmentApprovalAndFinalApprovalRace_exactlyOneOutcome() throws Exception {
        // 单阶段许可，全部检查通过并验收完成；登记影响该阶段的变更
        Permit permit = permitService.createPermit("变更并发许可", List.of(
                new PermitService.StageDef("唯一阶段", List.of(
                        new PermitService.ItemDef("A1", "检查项A1")))));
        Long stageId = stageRepository.findByPermitIdOrderBySeq(permit.getId()).get(0).getId();
        InspectionItemDefinition item = itemDefinitionRepository.findByStageIdOrderById(stageId).get(0);
        inspectionService.submitInspection("SUB-AM1", item.getId(), Conclusion.PASS, "张三", "合格", 1, 1);
        inspectionService.acceptStage(stageId);
        Amendment amendment = amendmentService.createAmendment(permit.getId(), "并发变更", List.of(stageId));

        // 并发：变更批准 vs 最终批准（共用许可行锁）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> approveAmendment = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> amendmentService.approveAmendment(amendment.getId(), 1));
        });
        Future<?> finalApprove = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> approvalService.approve(permit.getId(), 1));
        });
        ready.await();
        start.countDown();
        approveAmendment.get(30, TimeUnit.SECONDS);
        finalApprove.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        boolean approved = finalApprovalRepository.findByPermitId(permit.getId()).isPresent();
        boolean amendmentApproved = amendmentRepository.findById(amendment.getId()).orElseThrow()
                .getStatus() == AmendmentStatus.APPROVED;
        // 只能形成"已最终批准（变更被拒绝）"或"变更已批准（阶段回退、批准被拒绝）"一种结果
        assertTrue(approved ^ amendmentApproved,
                "approved=" + approved + ", amendmentApproved=" + amendmentApproved);
    }

    @Test
    void amendmentApprovalAndInspectionRace_noStaleRecordOnAmendmentVersion() throws Exception {
        // 单阶段许可，尚未提交检查；登记影响该阶段的变更
        Permit permit = permitService.createPermit("变更检查并发许可", List.of(
                new PermitService.StageDef("唯一阶段", List.of(
                        new PermitService.ItemDef("A1", "检查项A1")))));
        Long stageId = stageRepository.findByPermitIdOrderBySeq(permit.getId()).get(0).getId();
        Long itemId = itemDefinitionRepository.findByStageIdOrderById(stageId).get(0).getId();
        Amendment amendment = amendmentService.createAmendment(permit.getId(), "并发变更", List.of(stageId));

        // 并发：变更批准 vs 基于旧方案/旧工程版本的检查提交（共用阶段行锁）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> approveAmendment = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> amendmentService.approveAmendment(amendment.getId(), 1));
        });
        Future<?> submit = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> inspectionService.submitInspection(
                    "SUB-RACE", itemId, Conclusion.PASS, "张三", "合格", 1, 1));
        });
        ready.await();
        start.countDown();
        approveAmendment.get(30, TimeUnit.SECONDS);
        submit.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 无论谁先提交：变更版本上不允许出现基于旧方案的检查记录
        WorkVersion latest = versionRepository.findTopByStageIdOrderByVersionNumberDesc(stageId)
                .orElseThrow();
        assertEquals(VersionReason.AMENDMENT, latest.getReason());
        assertTrue(recordRepository.findByVersionId(latest.getId()).isEmpty());
        // 过期提交若曾生效，只能落在旧版本上（随后被变更失效），新版本必须重新检查
        assertEquals(StageStatus.ACTIVE, stageRepository.findById(stageId).orElseThrow().getStatus());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void ignoreBusiness(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ignored) {
            // 并发下必有一方因业务规则失败，最终状态由断言校验
        }
    }
}
