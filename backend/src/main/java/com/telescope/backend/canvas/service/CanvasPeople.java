package com.telescope.backend.canvas.service;

import com.telescope.backend.canvas.dto.UserRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 캔버스 응답에 붙일 사람 정보(이름·부서)를 user 서비스에서 읽는다(#149). 캔버스는 user 저장소를 직접 쓰지 않는다.
 * 응답 하나에 필요한 사람을 모아 한 번에 읽는다(목록에서 사람마다 조회하지 않게).
 */
@Component
@RequiredArgsConstructor
public class CanvasPeople {

    private final UserDirectory userDirectory;

    /** 여러 사람을 한 번에 읽는다. 없는 사용자도 키는 있고 이름은 비어 있다. null ID는 건너뛴다. */
    public Map<Long, UserRef> refs(Collection<Long> ids) {
        Map<Long, UserRef> found = userDirectory.find(ids);
        Map<Long, UserRef> refs = new HashMap<>();
        for (Long id : ids) {
            if (id != null) {
                refs.put(id, found.getOrDefault(id, UserRef.unknown(id)));
            }
        }
        return refs;
    }

    /** 한 사람을 읽는다. */
    public UserRef ref(Long id) {
        return refs(List.of(id)).get(id);
    }

    /** 읽어 둔 표에서 꺼낸다. 표에 없으면 이름 없이 ID만. */
    static UserRef of(Map<Long, UserRef> refs, Long id) {
        return id == null ? null : refs.getOrDefault(id, UserRef.unknown(id));
    }
}
