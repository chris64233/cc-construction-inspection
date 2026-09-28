package com.chris64233.constructioninspection.domain;

public enum StageStatus {
    /** 前置阶段未完成，不可申请检查 */
    PENDING,
    /** 当前可提交检查、整改与验收 */
    ACTIVE,
    /** 阶段已一次性验收完成 */
    COMPLETED,
    /**
     * 已完成或已开始的阶段被方案变更打回，须按新方案版本重新检查；
     * 在其前置阶段重新验收完成前不可提交检查，前置就绪后由阶段验收流程恢复为 ACTIVE。
     */
    SUSPENDED
}
