package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.Stage;
import com.chris64233.constructioninspection.service.PermitService;
import com.chris64233.constructioninspection.web.dto.Requests.CreatePermitRequest;
import com.chris64233.constructioninspection.web.dto.Responses.PermitView;
import com.chris64233.constructioninspection.web.dto.Responses.StageView;
import jakarta.validation.Valid;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PermitView create(@Valid @RequestBody CreatePermitRequest request) {
        List<PermitService.StageDefinition> defs = request.stages().stream()
                .map(s -> new PermitService.StageDefinition(s.sequence(), s.name(),
                        s.items().stream()
                                .map(i -> new PermitService.ItemDefinition(i.itemCode(), i.name()))
                                .toList()))
                .toList();
        Permit permit = permitService.createPermit(request.permitNo(), request.projectName(), defs);
        return PermitView.of(permit, permit.getStages());
    }

    @GetMapping("/{permitId}")
    public PermitView get(@PathVariable Long permitId) {
        Permit permit = permitService.getPermit(permitId);
        return PermitView.of(permit, permitService.listStages(permitId));
    }

    @GetMapping("/{permitId}/stages")
    public List<StageView> stages(@PathVariable Long permitId) {
        return permitService.listStages(permitId).stream().map(StageView::of).toList();
    }
}
