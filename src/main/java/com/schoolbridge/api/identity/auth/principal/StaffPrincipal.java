package com.schoolbridge.api.identity.auth.principal;

import com.schoolbridge.api.identity.UserRole;
import java.util.UUID;

public record StaffPrincipal(UUID userId, UUID schoolId, UserRole role)
    implements SchoolScopedPrincipal {}
