package com.telescope.auth.user.service;

import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.auth.user.dto.UserResponse;
import com.telescope.auth.user.dto.UserSearchResponse;
import com.telescope.auth.user.dto.UserSummary;
import com.telescope.auth.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 사용자 조회·검색. */
@Service
@RequiredArgsConstructor
public class UserService {

    /** 검색 결과 최대 인원(frontend api-spec 2.3). */
    static final int SEARCH_LIMIT = 6;

    private final UserRepository userRepository;

    /**
     * 본인 정보. 권한: 로그인한 본인.
     *
     * @throws BusinessException 토큰은 유효하지만 사용자가 없으면(삭제 등) {@code A002}
     */
    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    /**
     * 여러 사람의 표시 정보(이름·부서)를 한 번의 조회로 돌려준다(#149). 캔버스처럼 다른 기능이 응답에 사람 이름을 붙일 때 쓴다.
     * 없는 사용자는 결과에서 빠진다. 권한: 서버 안에서만 부른다(API로 직접 열지 않음).
     */
    @Transactional(readOnly = true)
    public Map<Long, UserSummary> summaries(Collection<Long> ids) {
        Set<Long> distinct = new LinkedHashSet<>(ids);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserSummary> byId = new HashMap<>();
        userRepository.findAllById(distinct).forEach(u -> byId.put(u.getId(), UserSummary.from(u)));
        return byId;
    }

    /**
     * 사번·이름·부서 일부로 사용자를 찾는다(최대 {@value #SEARCH_LIMIT}명, 이름순). 권한: 로그인한 사용자 누구나.
     * 검색어가 비면 빈 목록이다.
     */
    @Transactional(readOnly = true)
    public List<UserSearchResponse> search(String q) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.isEmpty()) {
            return List.of();
        }
        return userRepository.search(keyword, Limit.of(SEARCH_LIMIT)).stream()
                .map(UserSearchResponse::from)
                .toList();
    }
}
