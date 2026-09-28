package com.chris64233.constructioninspection.domain;

public enum AmendmentStatus {
    /** 已登记，待批准 */
    PENDING,
    /** 已批准，已生成新方案版本并使受影响阶段检查结果失效 */
    APPROVED
}
