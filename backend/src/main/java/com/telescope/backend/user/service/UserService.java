package com.telescope.backend.user.service;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.user.dto.UserResponse;
import com.telescope.backend.user.dto.UserSearchResponse;
import com.telescope.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
