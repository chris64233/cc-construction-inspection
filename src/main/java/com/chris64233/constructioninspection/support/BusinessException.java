package com.chris64233.constructioninspection.support;

/** 业务规则冲突（HTTP 409） */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
