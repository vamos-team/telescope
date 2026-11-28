package com.telescope.auth.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청.
 *
 * @param employeeNo 사번
 * @param password   비밀번호(평문). 로그·응답에 남기지 않는다
 */
public record LoginRequest(
        @NotBlank @Size(max = 100) String employeeNo,
        @NotBlank String password) {

    /** 비밀번호가 로그에 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "LoginRequest[employeeNo=" + employeeNo + ", password=***]";
    }
}
