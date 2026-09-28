package com.chris64233.constructioninspection.domain;

public enum AmendmentStatus {
    /** 已提交、待批准 */
    PROPOSED,
    /** 已批准：已生成新方案版本并完成受影响阶段的失效处理 */
    APPROVED
}
