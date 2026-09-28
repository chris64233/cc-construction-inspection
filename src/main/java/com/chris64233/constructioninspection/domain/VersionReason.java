package com.chris64233.constructioninspection.domain;

public enum VersionReason {
    /** 阶段激活时创建的初始工程版本 */
    INITIAL,
    /** 整改提交后产生的复检版本 */
    RECTIFICATION,
    /** 方案变更批准后受影响阶段产生的新版本 */
    AMENDMENT
}
