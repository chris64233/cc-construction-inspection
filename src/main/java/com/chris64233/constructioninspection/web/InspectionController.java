package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.service.InspectionService;
import com.chris64233.constructioninspection.web.dto.Requests.SubmitInspectionRequest;
import com.chris64233.constructioninspection.web.dto.Requests.SubmitRectificationRequest;
import com.chris64233.constructioninspection.web.dto.Responses.InspectionResultView;
import com.chris64233.constructioninspection.web.dto.Responses.InspectionView;
import com.chris64233.constructioninspection.web.dto.Responses.RectificationView;
import com.chris64233.constructioninspection.web.dto.Responses.StageView;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/permits/{permitId}/stages/{sequence}")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @PostMapping("/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    public InspectionResultView submitInspection(@PathVariable Long permitId,
                                                 @PathVariable int sequence,
                                                 @Valid @RequestBody SubmitInspectionRequest request) {
        return InspectionResultView.of(inspectionService.submitInspection(
                permitId, sequence, request.itemCode(), request.conclusion(),
                request.inspector(), request.evidence(), request.submissionNo()));
    }

    @PostMapping("/rectifications/{rectificationId}/submit")
    public RectificationView submitRectification(@PathVariable Long permitId,
                                                 @PathVariable int sequence,
                                                 @PathVariable Long rectificationId,
                                                 @Valid @RequestBody SubmitRectificationRequest request) {
        return RectificationView.of(inspectionService.submitRectification(
                permitId, sequence, rectificationId, request.submissionNo()));
    }

    @PostMapping("/acceptance")
    public StageView accept(@PathVariable Long permitId, @PathVariable int sequence) {
        return StageView.of(inspectionService.acceptStage(permitId, sequence));
    }

    @GetMapping("/inspections")
    public List<InspectionView> inspections(@PathVariable Long permitId,
                                            @PathVariable int sequence,
                                            @RequestParam(required = false) Integer version) {
        return inspectionService.listInspections(permitId, sequence, version).stream()
                .map(InspectionView::of).toList();
    }

    @GetMapping("/rectifications")
    public List<RectificationView> rectifications(@PathVariable Long permitId,
                                                  @PathVariable int sequence) {
        return inspectionService.listRectifications(permitId, sequence).stream()
                .map(RectificationView::of).toList();
    }
}
