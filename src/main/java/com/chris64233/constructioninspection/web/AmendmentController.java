package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.service.AmendmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AmendmentController {

    private final AmendmentService amendmentService;

    public AmendmentController(AmendmentService amendmentService) {
        this.amendmentService = amendmentService;
    }

    public record CreateAmendmentRequest(@NotBlank String description,
                                         @NotEmpty List<Long> affectedStageIds) {
    }

    /** 登记方案变更：标明受影响阶段 */
    @PostMapping("/permits/{permitId}/amendments")
    @ResponseStatus(HttpStatus.CREATED)
    public Views.AmendmentView create(@PathVariable Long permitId,
                                      @Valid @RequestBody CreateAmendmentRequest request) {
        Amendment amendment = amendmentService.createAmendment(
                permitId, request.description(), request.affectedStageIds());
        return Views.AmendmentView.of(amendment, null);
    }

    public record ApproveAmendmentRequest(@NotNull Integer expectedPlanVersion) {
    }

    /** 批准变更：生成新方案版本，仅受影响阶段的检查结果失效 */
    @PostMapping("/amendments/{amendmentId}/approve")
    public Views.AmendmentView approve(@PathVariable Long amendmentId,
                                       @Valid @RequestBody ApproveAmendmentRequest request) {
        Amendment amendment = amendmentService.approveAmendment(amendmentId, request.expectedPlanVersion());
        return Views.AmendmentView.of(amendment,
                amendmentService.planVersionOf(amendmentId).orElse(null));
    }

    /** 变更单查询 */
    @GetMapping("/permits/{permitId}/amendments")
    public List<Views.AmendmentView> amendments(@PathVariable Long permitId) {
        return amendmentService.listAmendments(permitId).stream()
                .map(a -> Views.AmendmentView.of(a,
                        amendmentService.planVersionOf(a.getId()).orElse(null)))
                .toList();
    }

    /** 方案版本查询 */
    @GetMapping("/permits/{permitId}/plan-versions")
    public List<Views.PlanVersionView> planVersions(@PathVariable Long permitId) {
        return amendmentService.listPlanVersions(permitId).stream()
                .map(Views.PlanVersionView::of)
                .toList();
    }
}
