package com.chris64233.constructioninspection.service;

import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.Stage;
import com.chris64233.constructioninspection.domain.StageItem;
import com.chris64233.constructioninspection.repo.PermitRepository;
import com.chris64233.constructioninspection.repo.StageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PermitService {

    private final PermitRepository permitRepository;
    private final StageRepository stageRepository;

    public PermitService(PermitRepository permitRepository, StageRepository stageRepository) {
        this.permitRepository = permitRepository;
        this.stageRepository = stageRepository;
    }

    public record StageDefinition(int sequence, String name, List<ItemDefinition> items) {
    }

    public record ItemDefinition(String itemCode, String name) {
    }

    @Transactional
    public Permit createPermit(String permitNo, String projectName, List<StageDefinition> stageDefs) {
        if (permitRepository.existsByPermitNo(permitNo)) {
            throw BusinessException.conflict("PERMIT_NO_EXISTS", "许可编号已存在: " + permitNo);
        }
        if (stageDefs == null || stageDefs.isEmpty()) {
            throw BusinessException.unprocessable("STAGES_REQUIRED", "许可必须定义至少一个施工阶段");
        }
        Set<Integer> sequences = new HashSet<>();
        Permit permit = new Permit(permitNo, projectName);
        for (StageDefinition def : stageDefs) {
            if (!sequences.add(def.sequence())) {
                throw BusinessException.unprocessable("DUPLICATE_STAGE_SEQUENCE", "阶段顺序号重复: " + def.sequence());
            }
            if (def.items() == null || def.items().isEmpty()) {
                throw BusinessException.unprocessable("ITEMS_REQUIRED", "阶段必须定义至少一个检查项: " + def.sequence());
            }
            Stage stage = new Stage(def.sequence(), def.name());
            Set<String> itemCodes = new HashSet<>();
            for (ItemDefinition item : def.items()) {
                if (!itemCodes.add(item.itemCode())) {
                    throw BusinessException.unprocessable("DUPLICATE_ITEM_CODE",
                            "阶段 " + def.sequence() + " 内检查项编码重复: " + item.itemCode());
                }
                stage.addItem(new StageItem(item.itemCode(), item.name()));
            }
            permit.addStage(stage);
        }
        return permitRepository.save(permit);
    }

    @Transactional(readOnly = true)
    public Permit getPermit(Long permitId) {
        return permitRepository.findById(permitId)
                .orElseThrow(() -> BusinessException.notFound("许可不存在: " + permitId));
    }

    @Transactional(readOnly = true)
    public List<Stage> listStages(Long permitId) {
        getPermit(permitId);
        return stageRepository.findByPermitIdOrderBySequenceAsc(permitId);
    }
}
