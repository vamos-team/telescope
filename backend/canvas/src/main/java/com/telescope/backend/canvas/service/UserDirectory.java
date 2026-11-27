package com.telescope.backend.canvas.service;

import com.telescope.backend.canvas.dto.UserRef;

import java.util.Collection;
import java.util.Map;

public interface UserDirectory {

    Map<Long, UserRef> find(Collection<Long> ids);
}
