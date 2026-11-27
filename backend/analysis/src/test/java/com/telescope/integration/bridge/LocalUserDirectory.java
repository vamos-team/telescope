package com.telescope.integration.bridge;

import com.telescope.canvas.canvas.dto.UserRef;
import com.telescope.canvas.canvas.service.UserDirectory;
import com.telescope.auth.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** 통합 테스트에서 canvas를 같은 프로세스로 띄울 때 쓰는 사용자 조회. 운영은 각 서비스가 HTTP로 한다. */
@Component
@RequiredArgsConstructor
public class LocalUserDirectory implements UserDirectory {

    private final UserService userService;

    @Override
    public Map<Long, UserRef> find(Collection<Long> ids) {
        Map<Long, UserRef> found = new HashMap<>();
        userService.summaries(ids).forEach((id, u) -> found.put(id, new UserRef(id, u.name(), u.dept())));
        return found;
    }
}
