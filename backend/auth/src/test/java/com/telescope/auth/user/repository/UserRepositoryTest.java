package com.telescope.auth.user.repository;

import com.telescope.integration.IntegrationTestApplication;
import org.springframework.test.context.ContextConfiguration;
import com.telescope.analysis.TestcontainersConfig;
import com.telescope.common.config.JpaAuditingConfig;
import com.telescope.auth.user.entity.User;
import com.telescope.auth.user.entity.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V13 마이그레이션이 적용된 PostgreSQL에서 {@code ddl-auto: validate}를 통과하는지,
 * 사번 조회·중복 확인·부분 검색이 맞게 동작하는지 확인한다.
 */
@DataJpaTest
@ContextConfiguration(classes = IntegrationTestApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, JpaAuditingConfig.class})
class UserRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    @Test
    void 저장한_사용자를_사번으로_찾고_역할은_문자열로_저장된다() {
        User saved = save("E1001", "Kim", "Finance", "Planner", UserRole.ADMIN);
        em.flush();
        em.clear();

        User found = userRepository.findByEmployeeNo("E1001").orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getName()).isEqualTo("Kim");
        assertThat(found.getDept()).isEqualTo("Finance");
        assertThat(found.getJob()).isEqualTo("Planner");
        assertThat(found.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(em.createNativeQuery("select role from app.users where id = :id")
                .setParameter("id", saved.getId()).getSingleResult()).isEqualTo("ADMIN");
    }

    @Test
    void 부서와_직무가_없어도_저장된다() {
        save("E1002", "Lee", null, null, UserRole.USER);
        em.flush();
        em.clear();

        User found = userRepository.findByEmployeeNo("E1002").orElseThrow();

        assertThat(found.getDept()).isNull();
        assertThat(found.getJob()).isNull();
    }

    @Test
    void 없는_사번은_빈_값이다() {
        assertThat(userRepository.findByEmployeeNo("NONE")).isEmpty();
    }

    @Test
    void 사번_중복을_확인한다() {
        save("E1003", "Park", "Finance", null, UserRole.USER);

        assertThat(userRepository.existsByEmployeeNo("E1003")).isTrue();
        assertThat(userRepository.existsByEmployeeNo("E9999")).isFalse();
    }

    @Test
    void 같은_사번은_두_번_저장되지_않는다() {
        save("E1004", "Choi", null, null, UserRole.USER);
        em.flush();

        // 리포지토리를 거쳐 flush해야 DB 예외가 스프링 예외로 바뀐다
        assertThatThrownBy(() -> userRepository.saveAndFlush(
                User.create("E1004", "hash", "Other", null, null, UserRole.USER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 사번_이름_부서_일부로_대소문자_없이_찾는다() {
        User byNo = save("X7700", "Alpha", "Sales", null, UserRole.USER);
        User byName = save("E2001", "Bxname", "Sales", null, UserRole.USER);
        User byDept = save("E2002", "Charlie", "XDept", null, UserRole.USER);
        save("E2003", "Delta", null, null, UserRole.USER);

        List<User> result = userRepository.search("x", Limit.of(10));

        assertThat(result).containsExactly(byNo, byName, byDept);
    }

    @Test
    void 검색어의_퍼센트와_밑줄은_글자_그대로_찾는다() {
        User literal = save("E3001", "a_b%c", null, null, UserRole.USER);
        save("E3002", "aXbYc", null, null, UserRole.USER);

        assertThat(userRepository.search("_b%", Limit.of(10))).containsExactly(literal);
    }

    @Test
    void 검색_결과는_정해진_개수까지만_돌려준다() {
        for (int i = 0; i < 8; i++) {
            save("L400" + i, "Limit" + i, null, null, UserRole.USER);
        }

        List<User> result = userRepository.search("L400", Limit.of(6));

        assertThat(result).hasSize(6);
        assertThat(result).extracting(User::getName)
                .containsExactly("Limit0", "Limit1", "Limit2", "Limit3", "Limit4", "Limit5");
    }

    private User save(String employeeNo, String name, String dept, String job, UserRole role) {
        return userRepository.save(User.create(employeeNo, "hash", name, dept, job, role));
    }
}
