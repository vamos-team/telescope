package com.telescope.backend.canvas.service;

import com.telescope.backend.canvas.dto.UserRef;

import java.util.Collection;
import java.util.Map;

/**
 * 캔버스가 사람 정보(이름·부서)를 읽는 창구. 사용자 정보는 user(인증) 쪽이 가지고 있어 캔버스는 이 인터페이스로만 읽는다.
 * 찾은 사람만 담아 돌려준다(없는 ID는 키가 없다).
 */
public interface UserDirectory {

    Map<Long, UserRef> find(Collection<Long> ids);
}
