package com.telescope.auth.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 첫 관리자 시드 설정. 환경변수({@code ADMIN_EMPLOYEE_NO}, {@code ADMIN_PASSWORD}, {@code ADMIN_NAME})로만 넣는다.
 *
 * <p>사번·이름은 앞뒤 공백을 지우고, 비어 있으면 {@code null}로 둔다. 비밀번호는 받은 그대로 둔다.
 *
 * @param employeeNo 관리자 사번. 비밀번호와 함께 비우면 시드하지 않는다. 회원가입에서는 이 사번을 받지 않는다
 * @param password   관리자 비밀번호(평문). 저장할 때 해시하며 로그에 남기지 않는다
 * @param name       표시 이름. 비우면 {@link #DEFAULT_NAME}
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(String employeeNo, String password, String name) {

    static final String DEFAULT_NAME = "관리자";

    public AdminProperties {
        employeeNo = trimToNull(employeeNo);
        name = trimToNull(name);
    }

    /** 관리자 사번으로 정해 둔 값인지. 시드 전에 일반 사용자가 이 사번을 먼저 가입해 관리자 자리를 막지 못하게 한다. */
    public boolean isAdminEmployeeNo(String candidate) {
        return employeeNo != null && employeeNo.equals(candidate);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 비밀번호가 로그에 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "AdminProperties[employeeNo=" + employeeNo + ", password=***, name=" + name + "]";
    }
}
