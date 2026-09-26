package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.service.PermitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
@RequestMapping("/api/permits")
public class PermitController {

    private final PermitService permitService;

    public PermitController(PermitService permitService) {
        this.permitService = permitService;
    }

    public record ItemDefRequest(@NotBlank String code, @NotBlank String name) {
    }

    public record StageDefRequest(@NotBlank String name, @NotEmpty List<@Valid ItemDefRequest> items) {
    }

    public record CreatePermitRequest(@NotBlank String name, @NotEmpty List<@Valid StageDefRequest> stages) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Views.PermitView create(@Valid @RequestBody CreatePermitRequest request) {
        List<PermitService.StageDef> stageDefs = request.stages().stream()
                .map(s -> new PermitService.StageDef(s.name(),
                        s.items().stream()
                                .map(i -> new PermitService.ItemDef(i.code(), i.name()))
                                .toList()))
                .toList();
        Permit permit = permitService.createPermit(request.name(), stageDefs);
        return Views.PermitView.of(permit);
    }

    /** 许可阶段查询 */
    @GetMapping("/{permitId}/stages")
    public List<Views.StageView> stages(@PathVariable Long permitId) {
        return permitService.listStages(permitId).stream()
                .map(s -> Views.StageView.of(s, permitService.listItemDefinitions(s.getId())))
                .toList();
    }
}
