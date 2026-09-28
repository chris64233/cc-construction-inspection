package com.chris64233.constructioninspection.domain;

public enum RectificationStatus {
    OPEN,
    CLOSED,
    /** 整改项所属阶段被方案变更影响：对应旧方案上的整改链整体失效，不再需要关闭 */
    CANCELLED
}
