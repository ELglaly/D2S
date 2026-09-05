package com.schoolbridge.api.identity.dto;

import com.schoolbridge.api.identity.User;
import org.springframework.stereotype.Component;

@Component
public class IdentityMapper {

  public UserResponse toResponse(User u) {
    return new UserResponse(
        u.getId(),
        u.getSchoolId(),
        u.getRole(),
        u.getName(),
        u.getEmail(),
        u.getStatus(),
        u.getCreatedAt(),
        u.getUpdatedAt());
  }
}
