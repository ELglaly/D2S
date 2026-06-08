package com.schoolbridge.api.identity;

import com.schoolbridge.api.identity.dto.CreateUserRequest;
import com.schoolbridge.api.identity.dto.UserResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

  UserResponse create(UUID schoolId, CreateUserRequest request);

  Page<UserResponse> list(UUID schoolId, Pageable pageable);

  UserResponse findById(UUID schoolId, UUID id);
}
