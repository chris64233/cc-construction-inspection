package com.chris64233.constructioninspection.service;

import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.VersionReason;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.InspectionItemDefinitionRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.repository.WorkVersionRepository;
import com.chris64233.constructioninspection.support.BusinessException;
import com.chris64233.constructioninspection.support.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PermitService {

    private final PermitRepository permitRepository;
    private final ConstructionStageRepository stageRepository;
    private final InspectionItemDefinitionRepository itemDefinitionRepository;
    private final WorkVersionRepository versionRepository;

    public PermitService(PermitRepository permitRepository,
                         ConstructionStageRepository stageRepository,
                         InspectionItemDefinitionRepository itemDefinitionRepository,
                         WorkVersionRepository versionRepository) {
        this.permitRepository = permitRepository;
        this.stageRepository = stageRepository;
        this.itemDefinitionRepository = itemDefinitionRepository;
        this.versionRepository = versionRepository;
    }

    public record ItemDef(String code, String name) {
    }

    public record StageDef(String name, List<ItemDef> items) {
    }

    /**
     * 创建许可：按定义顺序建立施工阶段与每阶段所需检查项。
     * 第一个阶段置为 ACTIVE 并生成初始工程版本 v1，其余阶段 PENDING。
     */
    @Transactional
    public Permit createPermit(String name, List<StageDef> stageDefs) {
        if (stageDefs == null || stageDefs.isEmpty()) {
            throw new BusinessException("许可至少定义一个施工阶段");
        }
        Permit permit = permitRepository.save(new Permit(name));
        int seq = 1;
        for (StageDef def : stageDefs) {
            if (def.items() == null || def.items().isEmpty()) {
                throw new BusinessException("阶段[" + def.name() + "]至少定义一个检查项");
            }
            boolean first = seq == 1;
            ConstructionStage stage = stageRepository.save(new ConstructionStage(
                    permit, seq, def.name(), first ? StageStatus.ACTIVE : StageStatus.PENDING));
            for (ItemDef item : def.items()) {
                itemDefinitionRepository.save(new InspectionItemDefinition(stage, item.code(), item.name()));
            }
            if (first) {
                versionRepository.save(new WorkVersion(stage, 1, VersionReason.INITIAL));
            }
            seq++;
        }
        return permit;
    }

    @Transactional(readOnly = true)
    public Permit getPermit(Long permitId) {
        return permitRepository.findById(permitId)
                .orElseThrow(() -> new NotFoundException("许可不存在: " + permitId));
    }

    @Transactional(readOnly = true)
    public List<ConstructionStage> listStages(Long permitId) {
        getPermit(permitId);
        return stageRepository.findByPermitIdOrderBySeq(permitId);
    }

    @Transactional(readOnly = true)
    public List<InspectionItemDefinition> listItemDefinitions(Long stageId) {
        return itemDefinitionRepository.findByStageIdOrderById(stageId);
    }
}
