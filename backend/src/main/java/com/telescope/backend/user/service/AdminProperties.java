package com.telescope.backend.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 첫 관리자 시드 설정. 환경변수({@code ADMIN_EMPLOYEE_NO}, {@code ADMIN_PASSWORD}, {@code ADMIN_NAME})로만 넣는다.
 *
 * @param employeeNo 관리자 사번. 비밀번호와 함께 비우면 시드하지 않는다
 * @param password   관리자 비밀번호(평문). 저장할 때 해시하며 로그에 남기지 않는다
 * @param name       표시 이름. 비우면 {@link #DEFAULT_NAME}
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(String employeeNo, String password, String name) {

    static final String DEFAULT_NAME = "관리자";

    /** 비밀번호가 로그에 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "AdminProperties[employeeNo=" + employeeNo + ", password=***, name=" + name + "]";
    }
}
