package com.schoolbridge.api.common.security;

import com.schoolbridge.api.common.error.AuthenticationException;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.PlatformAdminPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.identity.otp.OtpService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the request principal from a single {@code Authorization: Bearer ...} header. A
 * dot-bearing value is treated as a staff RS256 JWT; anything else is an opaque parent token looked
 * up in Redis. Invalid/expired tokens trigger a 401 via the configured authentication entry point
 * so the client knows their credential is bad rather than missing.
 */
public class BearerAuthenticationFilter extends OncePerRequestFilter {

  private static final String HEADER = "Authorization";
  private static final String PREFIX = "Bearer ";

  private final JwtService jwtService;
  private final OtpService otpService;
  private final RestAuthenticationEntryPoint entryPoint;

  public BearerAuthenticationFilter(
      JwtService jwtService, OtpService otpService, RestAuthenticationEntryPoint entryPoint) {
    this.jwtService = jwtService;
    this.otpService = otpService;
    this.entryPoint = entryPoint;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader(HEADER);
    if (header == null || !header.startsWith(PREFIX)) {
      filterChain.doFilter(request, response);
      return;
    }
    Authentication current = SecurityContextHolder.getContext().getAuthentication();
    if (current != null && !(current instanceof AnonymousAuthenticationToken)) {
      filterChain.doFilter(request, response);
      return;
    }

    String token = header.substring(PREFIX.length()).trim();
    try {
      AbstractAuthenticationToken auth =
          token.contains(".") ? authenticateJwt(token) : authenticateOpaque(token);
      SecurityContextHolder.getContext().setAuthentication(auth);
    } catch (AuthenticationException ex) {
      SecurityContextHolder.clearContext();
      entryPoint.commence(request, response, null);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private AbstractAuthenticationToken authenticateJwt(String token) {
    Claims claims = jwtService.parse(token);
    String kind = claims.get("kind", String.class);
    UUID subjectId = UUID.fromString(claims.getSubject());

    // NOTE: the 3-arg UsernamePasswordAuthenticationToken constructor already marks the token
    // authenticated. Calling setAuthenticated(true) on the resulting token throws because the
    // override is a deliberate trip-wire against unauthorized upgrades.
    if ("PLATFORM_ADMIN".equals(kind)) {
      return new UsernamePasswordAuthenticationToken(
          new PlatformAdminPrincipal(subjectId),
          null,
          List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
    }
    if ("USER".equals(kind)) {
      UserRole role = UserRole.valueOf(claims.get("role", String.class));
      UUID schoolId = UUID.fromString(claims.get("schoolId", String.class));
      return new UsernamePasswordAuthenticationToken(
          new StaffPrincipal(subjectId, schoolId, role),
          null,
          List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }
    throw new AuthenticationException("error.auth.token_invalid");
  }

  private AbstractAuthenticationToken authenticateOpaque(String token) {
    OtpService.ParentSession session = otpService.resolve(token);
    if (session == null) {
      throw new AuthenticationException("error.auth.token_invalid");
    }
    return new UsernamePasswordAuthenticationToken(
        new ParentPrincipal(session.userId(), session.schoolId()),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_PARENT")));
  }
}

