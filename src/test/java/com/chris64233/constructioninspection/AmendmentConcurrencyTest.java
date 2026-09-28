package com.chris64233.constructioninspection;

import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.repository.AmendmentRepository;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.FinalApprovalRepository;
import com.chris64233.constructioninspection.repository.InspectionItemDefinitionRepository;
import com.chris64233.constructioninspection.repository.InspectionRecordRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.repository.PlanVersionRepository;
import com.chris64233.constructioninspection.repository.RectificationRepository;
import com.chris64233.constructioninspection.repository.StageAcceptanceRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案变更并发规则测试：
 * 变更批准与检查提交、阶段验收、最终使用批准并发时，通过方案/检查版本与行锁拒绝过期操作，
 * 最终状态始终自洽，不会出现"已批准但依据已失效"。
 * 不使用测试级事务，每个服务调用在各自事务中执行。
 */
@SpringBootTest
class AmendmentConcurrencyTest {

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
    PlanVersionRepository planVersionRepository;
    @Autowired
    AmendmentRepository amendmentRepository;
    @Autowired
    StageAcceptanceRepository acceptanceRepository;

    @BeforeEach
    @AfterEach
    void cleanup() {
        acceptanceRepository.deleteAll();
        rectificationRepository.deleteAll();
        recordRepository.deleteAll();
        versionRepository.deleteAll();
        planVersionRepository.deleteAll();
        itemDefinitionRepository.deleteAll();
        amendmentRepository.deleteAll();
        stageRepository.deleteAll();
        stopWorkOrderRepository.deleteAll();
        finalApprovalRepository.deleteAll();
        permitRepository.deleteAll();
    }

    private Permit singleStagePermit() {
        return permitService.createPermit("并发变更许可", List.of(
                new PermitService.StageDef("唯一阶段", List.of(
                        new PermitService.ItemDef("A1", "检查项A1")))));
    }

    @Test
    void amendmentApprovalAndInspectionSubmit_serializedByStageLock() throws Exception {
        Permit permit = singleStagePermit();
        Long stageId = stageRepository.findByPermitIdOrderBySeq(permit.getId()).get(0).getId();
        Long itemId = itemDefinitionRepository.findByStageIdOrderById(stageId).get(0).getId();
        // 先有一条 v1 有效结果
        inspectionService.submitInspection("SUB-OLD", itemId, Conclusion.PASS, "张三", "合格");
        var amendment = amendmentService.propose(permit.getId(), "方案调整", List.of(stageId));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> approveAmendment = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> amendmentService.approve(amendment.getId(), null));
        });
        // 并发提交针对同一检查项、同一旧版本的另一条结果（用不同提交号）
        Future<?> submit = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> inspectionService.submitInspection(
                    "SUB-RACE", itemId, Conclusion.PASS, "李四", "并发结果"));
        });
        ready.await();
        start.countDown();
        approveAmendment.get(30, TimeUnit.SECONDS);
        submit.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        boolean planAdvanced = permitRepository.findById(permit.getId()).orElseThrow()
                .getCurrentPlanVersionNumber() == 2;
        assertTrue(planAdvanced);
        // 无论谁先获得阶段锁，旧版本 v1 上都不得残留有效结果
        List<Long> versionIds = versionRepository.findByStageIdOrderByVersionNumber(stageId).stream()
                .map(v -> v.getId()).toList();
        long validOnOldVersion = recordRepository.findByVersionId(versionIds.get(0)).stream()
                .filter(r -> !r.isInvalidated()).count();
        assertEquals(0, validOnOldVersion, "旧版本上不得残留有效结果");
        // 并发提交的唯一两种合法结局：
        //  - 变更先：它在变更提交后落锁，结果落在新版本 v2 且有效；
        //  - 提交先：结果落在 v1，随后被批量失效。
        recordRepository.findBySubmissionNo("SUB-RACE").ifPresent(race -> {
            var raceVersion = versionRepository.findById(race.getVersion().getId()).orElseThrow();
            if (!race.isInvalidated()) {
                assertEquals(2, raceVersion.getVersionNumber(), "有效结果必须在新版本 v2 上");
                assertEquals(2, raceVersion.getPlanVersionNumber());
            } else {
                assertEquals(1, raceVersion.getVersionNumber(), "被失效结果来自旧版本 v1");
            }
        });
        // 新版本是 AMENDMENT v2
        var latest = versionRepository.findTopByStageIdOrderByVersionNumberDesc(stageId).orElseThrow();
        assertEquals(2, latest.getVersionNumber());
        assertEquals(2, latest.getPlanVersionNumber());
    }

    @Test
    void amendmentAndFinalApprovalRace_neverApprovesAgainstInvalidatedBasis() throws Exception {
        Permit permit = singleStagePermit();
        Long stageId = stageRepository.findByPermitIdOrderBySeq(permit.getId()).get(0).getId();
        Long itemId = itemDefinitionRepository.findByStageIdOrderById(stageId).get(0).getId();
        inspectionService.submitInspection("SUB-1", itemId, Conclusion.PASS, "张三", "合格");
        inspectionService.acceptStage(stageId);
        var amendment = amendmentService.propose(permit.getId(), "方案调整", List.of(stageId));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> approveAmendment = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> amendmentService.approve(amendment.getId(), null));
        });
        Future<?> finalApprove = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> approvalService.approve(permit.getId(), null));
        });
        ready.await();
        start.countDown();
        approveAmendment.get(30, TimeUnit.SECONDS);
        finalApprove.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        boolean finallyApproved = finalApprovalRepository.findByPermitId(permit.getId()).isPresent();
        boolean planAdvanced = permitRepository.findById(permit.getId()).orElseThrow()
                .getCurrentPlanVersionNumber() == 2;
        // 若变更先生效，最终批准必然被阻止（阶段已待复检）；若最终批准先生效，变更必然被阻止（方案停留在 v1）
        assertTrue(planAdvanced ^ finallyApproved,
                "planAdvanced=" + planAdvanced + ", finallyApproved=" + finallyApproved);
    }

    @Test
    void amendmentAndStageAcceptanceRace_stageEitherAcceptedOnV1OrReopenedOnV2() throws Exception {
        Permit permit = singleStagePermit();
        Long stageId = stageRepository.findByPermitIdOrderBySeq(permit.getId()).get(0).getId();
        Long itemId = itemDefinitionRepository.findByStageIdOrderById(stageId).get(0).getId();
        inspectionService.submitInspection("SUB-1", itemId, Conclusion.PASS, "张三", "合格");
        var amendment = amendmentService.propose(permit.getId(), "方案调整", List.of(stageId));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> accept = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> inspectionService.acceptStage(stageId));
        });
        Future<?> approveAmendment = pool.submit(() -> {
            ready.countDown();
            await(start);
            ignoreBusiness(() -> amendmentService.approve(amendment.getId(), null));
        });
        ready.await();
        start.countDown();
        accept.get(30, TimeUnit.SECONDS);
        approveAmendment.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        var stage = stageRepository.findById(stageId).orElseThrow();
        var latest = versionRepository.findTopByStageIdOrderByVersionNumberDesc(stageId).orElseThrow();
        // 无论谁先获得阶段锁，最终都收敛到同一自洽状态：方案 v2、阶段打回待复检、
        // 新工程版本 v2 无结果、v1 结果全部失效。
        // - 验收先提交：方案变更随后将其回退；
        // - 变更先提交：验收读到无结果的 v2，必然失败。
        assertEquals(2, permitRepository.findById(permit.getId()).orElseThrow()
                .getCurrentPlanVersionNumber());
        assertEquals(StageStatus.ACTIVE, stage.getStatus());
        assertEquals(2, latest.getVersionNumber());
        assertTrue(recordRepository.findByVersionIdAndInvalidatedFalse(latest.getId()).isEmpty());
        long validOnOld = recordRepository
                .findByVersionId(versionRepository.findByStageIdOrderByVersionNumber(stageId).get(0).getId())
                .stream().filter(r -> !r.isInvalidated()).count();
        assertEquals(0, validOnOld);
    }

    @Test
    void finalApprovalCarryingStalePlanVersionRejectedAfterAmendment() {
        Permit permit = singleStagePermit();
        Long stageId = stageRepository.findByPermitIdOrderBySeq(permit.getId()).get(0).getId();
        Long itemId = itemDefinitionRepository.findByStageIdOrderById(stageId).get(0).getId();
        inspectionService.submitInspection("SUB-1", itemId, Conclusion.PASS, "张三", "合格");
        inspectionService.acceptStage(stageId);
        // 已通过版本校验的最终批准请求（携带 v1）在方案变更生效后到达 → 被拒绝
        var amendment = amendmentService.propose(permit.getId(), "方案调整", List.of(stageId));
        amendmentService.approve(amendment.getId(), null);

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.chris64233.constructioninspection.support.BusinessException.class,
                () -> approvalService.approve(permit.getId(), 1));
        assertTrue(ex.getMessage().contains("v2"));
        assertFalse(finalApprovalRepository.findByPermitId(permit.getId()).isPresent());
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
