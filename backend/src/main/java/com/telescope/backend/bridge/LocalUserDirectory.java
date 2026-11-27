package com.telescope.backend.bridge;

import com.telescope.backend.canvas.dto.UserRef;
import com.telescope.backend.canvas.service.UserDirectory;
import com.telescope.backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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
