package com.telescope.canvas.canvas.service;

import com.telescope.canvas.canvas.dto.UserRef;

import java.util.Collection;
import java.util.Map;

public interface UserDirectory {

    Map<Long, UserRef> find(Collection<Long> ids);
}
