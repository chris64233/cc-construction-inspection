package com.chris64233.constructioninspection.domain;

public enum StageStatus {
    /** 前置阶段未完成，不可申请检查 */
    PENDING,
    /** 当前可提交检查、整改与验收 */
    ACTIVE,
    /** 阶段已一次性验收完成 */
    COMPLETED
}
