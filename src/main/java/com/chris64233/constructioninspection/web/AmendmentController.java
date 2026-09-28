package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.service.AmendmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AmendmentController {

    private final AmendmentService amendmentService;

    public AmendmentController(AmendmentService amendmentService) {
        this.amendmentService = amendmentService;
    }

    public record ProposeAmendmentRequest(@NotBlank String summary,
                                          @NotEmpty List<Long> affectedStageIds) {
    }

    /** 提交方案变更（标明受影响阶段） */
    @PostMapping("/permits/{permitId}/amendments")
    @ResponseStatus(HttpStatus.CREATED)
    public Views.AmendmentView propose(@PathVariable Long permitId,
                                       @Valid @RequestBody ProposeAmendmentRequest request) {
        Amendment amendment = amendmentService.propose(
                permitId, request.summary(), request.affectedStageIds());
        return toView(amendment);
    }

    /**
     * 批准方案变更，生成新方案版本并使受影响阶段的检查结果失效。
     * expectedBasePlanVersionNumber 为可选的并发保护参数：与变更基准不一致时拒绝过期操作。
     */
    @PostMapping("/amendments/{amendmentId}/approve")
    public Views.AmendmentApprovalView approve(
            @PathVariable Long amendmentId,
            @RequestParam(required = false) Integer expectedBasePlanVersionNumber) {
        AmendmentService.AmendmentApproval result =
                amendmentService.approve(amendmentId, expectedBasePlanVersionNumber);
        List<Views.AmendmentEffectView> effects = result.effects().stream()
                .map(e -> new Views.AmendmentEffectView(e.stageId(), e.stageSeq(), e.stageName(),
                        e.statusBefore(), e.statusAfter(), e.invalidatedRecordCount(),
                        e.cancelledRectificationCount(), e.newWorkVersionNumber(), e.mustReinspect()))
                .toList();
        return new Views.AmendmentApprovalView(toView(result.amendment()),
                result.planVersion().getVersionNumber(), effects);
    }

    /** 许可的变更单列表 */
    @GetMapping("/permits/{permitId}/amendments")
    public List<Views.AmendmentView> list(@PathVariable Long permitId) {
        return amendmentService.listAmendments(permitId).stream()
                .map(this::toView)
                .toList();
    }

    /** 变更单详情 */
    @GetMapping("/amendments/{amendmentId}")
    public Views.AmendmentView get(@PathVariable Long amendmentId) {
        return toView(amendmentService.getAmendment(amendmentId));
    }

    /** 许可的方案版本历史 */
    @GetMapping("/permits/{permitId}/plan-versions")
    public List<Map<String, Object>> planVersions(@PathVariable Long permitId) {
        return amendmentService.listPlanVersions(permitId).stream()
                .map(pv -> {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("id", pv.getId());
                    map.put("versionNumber", pv.getVersionNumber());
                    map.put("reason", pv.getReason().name());
                    map.put("amendmentId", pv.getAmendment() == null ? null : pv.getAmendment().getId());
                    map.put("createdAt", pv.getCreatedAt());
                    return map;
                })
                .toList();
    }

    private Views.AmendmentView toView(Amendment amendment) {
        // 阶段信息来自独立只读事务，按 seq 升序，避免触碰游离懒关联
        List<Views.AffectedStageView> stages = amendmentService
                .listAffectedStages(amendment.getId()).stream()
                .map(stage -> new Views.AffectedStageView(
                        stage.getId(), stage.getSeq(), stage.getName()))
                .toList();
        return Views.AmendmentView.of(amendment, stages);
    }
}
