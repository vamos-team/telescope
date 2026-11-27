package com.telescope.backend.user.entity;

import com.telescope.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 사용자. 다른 테이블은 숫자 {@code id}로 참조한다.
 *
 * <p>사번({@code employeeNo})은 바뀌거나 형식이 달라질 수 있는 업무 값이라 키로 쓰지 않고 로그인·검색·표시에만 쓴다.
 * 부서·직무는 표시용이며 권한 판단에 쓰지 않는다.
 */
@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String employeeNo;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String dept;

    @Column(length = 100)
    private String job;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    private User(String employeeNo, String passwordHash, String name, String dept, String job, UserRole role) {
        this.employeeNo = employeeNo;
        this.passwordHash = passwordHash;
        this.name = name;
        this.dept = dept;
        this.job = job;
        this.role = role;
    }

    /** 사용자를 만든다. {@code passwordHash}는 이미 해시한 값이어야 한다. 평문을 넘기지 않는다. */
    public static User create(String employeeNo, String passwordHash, String name, String dept, String job,
                              UserRole role) {
        return new User(employeeNo, passwordHash, name, dept, job, role);
    }
}
