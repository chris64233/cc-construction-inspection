package com.chris64233.constructioninspection.support;

/** 资源不存在（HTTP 404） */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
