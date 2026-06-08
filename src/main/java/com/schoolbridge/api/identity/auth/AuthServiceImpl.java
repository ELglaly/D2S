package com.schoolbridge.api.identity.auth;

import com.schoolbridge.api.common.error.AuthenticationException;
import com.schoolbridge.api.common.error.RateLimitException;
import com.schoolbridge.api.identity.PlatformAdmin;
import com.schoolbridge.api.identity.PlatformAdminRepository;
import com.schoolbridge.api.identity.RefreshToken;
import com.schoolbridge.api.identity.RefreshTokenRepository;
import com.schoolbridge.api.identity.SubjectKind;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserStatus;
import com.schoolbridge.api.identity.auth.dto.AuthResponse;
import com.schoolbridge.api.identity.auth.dto.LoginRequest;
import com.schoolbridge.api.identity.auth.dto.LogoutRequest;
import com.schoolbridge.api.identity.auth.dto.RefreshRequest;
import com.schoolbridge.api.identity.jwt.JwtService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

  private static final Duration REFRESH_TTL = Duration.ofDays(30);

  private final UserRepository userRepository;
  private final PlatformAdminRepository platformAdminRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final LoginRateLimiter rateLimiter;

  public AuthServiceImpl(
      UserRepository userRepository,
      PlatformAdminRepository platformAdminRepository,
      RefreshTokenRepository refreshTokenRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      LoginRateLimiter rateLimiter) {
    this.userRepository = userRepository;
    this.platformAdminRepository = platformAdminRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.rateLimiter = rateLimiter;
  }

  @Override
  @Transactional
  public AuthResponse login(LoginRequest request) {
    String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
    if (rateLimiter.isBlocked(email)) {
      throw new RateLimitException("error.auth.rate_limited");
    }

    // Try platform admin first (typically a much smaller table), then user.
    Optional<PlatformAdmin> admin = platformAdminRepository.findByEmail(email);
    if (admin.isPresent()
        && admin.get().getStatus() == UserStatus.ACTIVE
        && passwordEncoder.matches(request.password(), admin.get().getPasswordHash())) {
      rateLimiter.reset(email);
      return issueForPlatformAdmin(admin.get());
    }

    Optional<User> user = userRepository.findByEmail(email);
    if (user.isPresent()
        && user.get().getStatus() == UserStatus.ACTIVE
        && user.get().getPasswordHash() != null
        && passwordEncoder.matches(request.password(), user.get().getPasswordHash())) {
      rateLimiter.reset(email);
      return issueForUser(user.get());
    }

    rateLimiter.recordFailure(email);
    throw new AuthenticationException("error.auth.invalid_credentials");
  }

  @Override
  @Transactional
  public AuthResponse refresh(RefreshRequest request) {
    String hash = jwtService.hashRefresh(request.refreshToken());
    RefreshToken stored =
        refreshTokenRepository
            .findByTokenHash(hash)
            .orElseThrow(() -> new AuthenticationException("error.auth.token_invalid"));
    if (!stored.isActive(Instant.now())) {
      throw new AuthenticationException("error.auth.token_invalid");
    }

    if (stored.getSubjectKind() == SubjectKind.PLATFORM_ADMIN) {
      PlatformAdmin admin =
          platformAdminRepository
              .findById(stored.getSubjectId())
              .orElseThrow(() -> new AuthenticationException("error.auth.token_invalid"));
      if (admin.getStatus() != UserStatus.ACTIVE) {
        throw new AuthenticationException("error.auth.token_invalid");
      }
      return rotateAndIssue(stored, issueForPlatformAdmin(admin));
    }

    User user =
        userRepository
            .findById(stored.getSubjectId())
            .orElseThrow(() -> new AuthenticationException("error.auth.token_invalid"));
    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new AuthenticationException("error.auth.token_invalid");
    }
    return rotateAndIssue(stored, issueForUser(user));
  }

  @Override
  @Transactional
  public void logout(LogoutRequest request) {
    String hash = jwtService.hashRefresh(request.refreshToken());
    refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> token.revoke(null));
    // Unknown tokens: silently succeed (idempotent logout).
  }

  private AuthResponse issueForPlatformAdmin(PlatformAdmin admin) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("kind", "PLATFORM_ADMIN");
    claims.put("role", "SUPER_ADMIN");
    String access = jwtService.issueAccess(admin.getId().toString(), claims);
    String refresh = persistRefresh(SubjectKind.PLATFORM_ADMIN, admin.getId());
    return AuthResponse.bearer(access, refresh, Instant.now().plus(jwtService.accessTtl()));
  }

  private AuthResponse issueForUser(User user) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("kind", "USER");
    claims.put("role", user.getRole().name());
    claims.put("schoolId", user.getSchoolId().toString());
    String access = jwtService.issueAccess(user.getId().toString(), claims);
    String refresh = persistRefresh(SubjectKind.USER, user.getId());
    return AuthResponse.bearer(access, refresh, Instant.now().plus(jwtService.accessTtl()));
  }

  private String persistRefresh(SubjectKind kind, UUID subjectId) {
    String raw = jwtService.newRefreshToken();
    refreshTokenRepository.save(
        new RefreshToken(
            kind, subjectId, jwtService.hashRefresh(raw), Instant.now().plus(REFRESH_TTL)));
    return raw;
  }

  private AuthResponse rotateAndIssue(RefreshToken old, AuthResponse fresh) {
    old.revoke(jwtService.hashRefresh(fresh.refreshToken()));
    return fresh;
  }
}
