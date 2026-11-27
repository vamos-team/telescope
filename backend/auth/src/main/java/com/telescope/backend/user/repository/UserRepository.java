package com.telescope.backend.user.repository;

import com.telescope.backend.user.entity.User;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** 로그인할 때 사번으로 사용자를 찾는다. */
    Optional<User> findByEmployeeNo(String employeeNo);

    /** 사용자를 만들기 전에 사번이 이미 있는지 확인한다. */
    boolean existsByEmployeeNo(String employeeNo);

    /**
     * 사번·이름·부서 중 하나라도 {@code q}를 포함하는 사용자를 대소문자 구분 없이 찾는다.
     * 이름순으로 정렬하고, 이름이 같으면 ID순으로 정렬한다.
     *
     * <p>{@code q}에 든 {@code %}, {@code _}는 와일드카드가 아니라 글자 그대로 찾는다.
     */
    @Query("""
            select u from User u
            where lower(u.employeeNo) like lower(concat('%', :#{escape(#q)}, '%')) escape :#{escapeCharacter()}
               or lower(u.name) like lower(concat('%', :#{escape(#q)}, '%')) escape :#{escapeCharacter()}
               or lower(u.dept) like lower(concat('%', :#{escape(#q)}, '%')) escape :#{escapeCharacter()}
            order by u.name, u.id
            """)
    List<User> search(@Param("q") String q, Limit limit);
}
