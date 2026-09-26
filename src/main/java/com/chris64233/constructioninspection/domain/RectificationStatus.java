package com.chris64233.constructioninspection.domain;

public enum RectificationStatus {
    /** 检查不通过产生，待整改提交 */
    OPEN,
    /** 整改已提交，等待新版本复检 */
    SUBMITTED,
    /** 复检通过后关闭 */
    CLOSED
}
