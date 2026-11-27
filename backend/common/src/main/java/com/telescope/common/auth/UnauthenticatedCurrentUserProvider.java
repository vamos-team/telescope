package com.telescope.common.auth;

import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;

/**
 * 인증 구현 빈이 없을 때의 기본 구현. 누구도 인증된 사용자로 보지 않는다.
 *
 * <p>사용자 ID를 헤더 등으로 그대로 믿는 임시 구현을 두지 않는 이유: 배포되면 누구나 다른 사람으로
 * 위장할 수 있기 때문이다. 이 구현이 쓰이면 인증이 필요한 API는 모두 401을 돌려준다.
 */
public class UnauthenticatedCurrentUserProvider implements CurrentUserProvider {

    /** 항상 인증 실패({@code A002})를 던진다. */
    @Override
    public Long currentUserId() {
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}
