package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.service.InspectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    public record SubmitInspectionRequest(@NotBlank String submissionNo,
                                          @NotNull Long itemDefinitionId,
                                          @NotNull Conclusion conclusion,
                                          @NotBlank String inspector,
                                          @NotBlank String evidence,
                                          @NotNull Integer planVersion,
                                          @NotNull Integer stageVersion) {
    }

    @PostMapping("/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    public Views.RecordView submitInspection(@Valid @RequestBody SubmitInspectionRequest request) {
        InspectionRecord record = inspectionService.submitInspection(
                request.submissionNo(), request.itemDefinitionId(), request.conclusion(),
                request.inspector(), request.evidence(), request.planVersion(), request.stageVersion());
        return Views.RecordView.of(record);
    }

    public record SubmitRectificationRequest(@NotBlank String note, @NotNull Integer planVersion) {
    }

    /** 整改提交：关闭整改项并产生新的复检版本 */
    @PostMapping("/rectifications/{rectificationId}/submit")
    public Views.RectificationView submitRectification(@PathVariable Long rectificationId,
                                                       @Valid @RequestBody SubmitRectificationRequest request) {
        Rectification rectification = inspectionService.submitRectification(
                rectificationId, request.note(), request.planVersion());
        return Views.RectificationView.of(rectification);
    }

    /** 阶段一次性验收 */
    @PostMapping("/stages/{stageId}/accept")
    public Views.StageView acceptStage(@PathVariable Long stageId) {
        var stage = inspectionService.acceptStage(stageId);
        return new Views.StageView(stage.getId(), stage.getSeq(), stage.getName(),
                stage.getStatus().name(), stage.getAcceptedVersionId(), List.of());
    }

    /** 检查版本查询：版本及其上的检查记录 */
    @GetMapping("/stages/{stageId}/versions")
    public List<Views.VersionView> versions(@PathVariable Long stageId) {
        List<WorkVersion> versions = inspectionService.listVersions(stageId);
        List<Long> versionIds = versions.stream().map(WorkVersion::getId).toList();
        Map<Long, List<InspectionRecord>> recordsByVersion = inspectionService.listRecordsByVersions(versionIds)
                .stream()
                .collect(Collectors.groupingBy(r -> r.getVersion().getId()));
        return versions.stream()
                .map(v -> Views.VersionView.of(v, recordsByVersion.getOrDefault(v.getId(), List.of())))
                .toList();
    }

    /** 整改链查询 */
    @GetMapping("/stages/{stageId}/rectifications")
    public List<Views.RectificationView> rectifications(@PathVariable Long stageId) {
        return inspectionService.listRectificationChain(stageId).stream()
                .map(Views.RectificationView::of)
                .toList();
    }
}
