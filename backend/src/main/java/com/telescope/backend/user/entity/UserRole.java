package com.telescope.backend.user.entity;

/** 서비스 전체 역할. 공용 문서 수집·삭제는 {@link #ADMIN}만 할 수 있다. */
public enum UserRole {
    ADMIN,
    USER
}
