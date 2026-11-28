package com.telescope.auth.user.dto;

import com.telescope.common.auth.PasswordPolicy;
import com.telescope.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청. 역할은 받지 않는다(항상 {@code USER}).
 *
 * @param employeeNo 사번. 앞뒤 공백은 저장 전에 지운다
 * @param password   비밀번호(평문). 8자 이상, UTF-8 72바이트 이하. 로그·응답에 남기지 않는다
 * @param dept       부서(선택)
 * @param job        직무(선택)
 */
public record SignupRequest(
        @NotBlank @Size(max = 100) String employeeNo,
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH) @MaxUtf8Bytes(PasswordPolicy.MAX_BYTES) String password,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String dept,
        @Size(max = 100) String job) {

    /** 비밀번호가 로그에 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "SignupRequest[employeeNo=" + employeeNo + ", password=***, name=" + name + ", dept=" + dept
                + ", job=" + job + "]";
    }
}
