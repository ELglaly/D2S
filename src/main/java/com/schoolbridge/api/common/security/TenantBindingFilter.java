package com.schoolbridge.api.common.security;

import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.auth.principal.SchoolScopedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds {@link TenantContext} from the authenticated principal for the duration of the request,
 * then clears it on exit so no tenant leaks across threads. Platform-admin requests are
 * intentionally tenant-less; super-admin code paths that need to operate on a specific tenant must
 * use {@link TenantContext#runAs}.
 */
public class TenantBindingFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    boolean bound = false;
    if (auth != null && auth.getPrincipal() instanceof SchoolScopedPrincipal scoped) {
      TenantContext.set(scoped.schoolId());
      bound = true;
    }
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (bound) {
        TenantContext.clear();
      }
    }
  }
}

