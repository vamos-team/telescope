package com.telescope.canvas.canvas.service;

import com.telescope.canvas.canvas.dto.UserRef;
import org.springframework.context.annotation.Primary;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 테스트용 UserDirectory. 운영은 auth 서비스를 HTTP로 부르지만(HttpUserDirectory), 테스트는 이 스텁이
 * 양수 id는 존재하는 사용자로 보고(등록된 이름, 없으면 ID만) 조회 횟수를 센다(N+1 확인용). 음수·0 id는 없는 사용자다.
 */
@Primary
public class StubUserDirectory implements UserDirectory {

    private final Map<Long, UserRef> people = new HashMap<>();
    private int calls;

    public void register(Long id, String name, String dept) {
        people.put(id, new UserRef(id, name, dept));
    }

    public int calls() {
        return calls;
    }

    public void resetCalls() {
        calls = 0;
    }

    @Override
    public Map<Long, UserRef> find(Collection<Long> ids) {
        calls++;
        Map<Long, UserRef> found = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                found.put(id, people.getOrDefault(id, UserRef.unknown(id)));
            }
        }
        return found;
    }
}
