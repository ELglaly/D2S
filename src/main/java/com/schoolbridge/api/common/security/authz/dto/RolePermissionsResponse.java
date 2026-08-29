package com.schoolbridge.api.common.security.authz.dto;

import java.util.List;

/** The permissions currently granted to a role. */
public record RolePermissionsResponse(String role, List<String> permissions) {}

