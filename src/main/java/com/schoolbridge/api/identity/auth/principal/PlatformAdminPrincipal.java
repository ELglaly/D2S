package com.schoolbridge.api.identity.auth.principal;

import java.util.UUID;

/** Platform super-admin. Not tenant-scoped. */
public record PlatformAdminPrincipal(UUID adminId) {}

