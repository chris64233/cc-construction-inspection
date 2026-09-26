package com.chris64233.constructioninspection.web.dto;

import com.chris64233.constructioninspection.domain.Conclusion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class Requests {

    private Requests() {
    }

    public record CreatePermitRequest(
            @NotBlank String permitNo,
            @NotBlank String projectName,
            @NotEmpty List<@Valid StageDef> stages) {
    }

    public record StageDef(
            int sequence,
            @NotBlank String name,
            @NotEmpty List<@Valid ItemDef> items) {
    }

    public record ItemDef(@NotBlank String itemCode, @NotBlank String name) {
    }

    public record SubmitInspectionRequest(
            @NotBlank String itemCode,
            @NotNull Conclusion conclusion,
            @NotBlank String inspector,
            @NotBlank String evidence,
            @NotBlank String submissionNo) {
    }

    public record SubmitRectificationRequest(@NotBlank String submissionNo) {
    }

    public record IssueStopOrderRequest(@NotBlank String reason, @NotBlank String issuer) {
    }

    public record ApproveRequest(@NotBlank String approvedBy) {
    }
}
