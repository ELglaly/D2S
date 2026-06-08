package com.schoolbridge.api.identity.auth.principal;

import java.util.UUID;

public record ParentPrincipal(UUID userId, UUID schoolId) implements SchoolScopedPrincipal {}
