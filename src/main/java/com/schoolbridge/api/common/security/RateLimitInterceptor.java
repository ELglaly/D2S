package com.schoolbridge.api.common.security;

import com.schoolbridge.api.common.error.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A blanket per-caller request cap over the whole API.
 *
 * <p>Before this, 96 of 99 endpoints had no rate limit at all: only {@code POST /auth/login} and
 * the two assistant endpoints were protected. Everything else â€” enumerate students, replay
 * announcement reads, hammer attendance writes â€” was bounded only by how fast a client could
 * send.
 *
 * <p>Deliberately a {@link HandlerInterceptor} and not a servlet {@code Filter}. An exception
 * thrown from a filter escapes Spring MVC entirely and renders as a container error page, losing
 * the RFC-7807 body and the ar/en localisation every other error in this API carries. Interceptor
 * exceptions go through {@code DispatcherServlet}'s resolver chain, so {@link RateLimitException}
 * comes back as a normal localised 429.
 *
 * <p>This is a coarse backstop, not a replacement for the targeted limiters. {@code
 * LoginRateLimiter} still guards credential stuffing per email and {@code OtpRequestRateLimiter}
 * still caps OTP sends per phone, because those protect against harms (account takeover, message
 * spend) a per-IP cap cannot see: one IP can attack many accounts, and many IPs can attack one.
 *
 * <p>Authenticated callers are keyed by principal so a school behind a single NAT gateway does not
 * throttle itself; unauthenticated callers fall back to client IP.
 *
 * <p><b>Known gap.</b> Being an interceptor, this only sees requests that reach a handler. An
 * unauthenticated flood against a <i>protected</i> endpoint is rejected with 401 by the Spring
 * Security filter chain first and is never counted here. That is tolerable because such a 401 costs
 * no database work â€” but it means volumetric abuse of protected paths is an edge/WAF concern, not
 * something this class solves. Public endpoints, which are the ones that do real work while
 * unauthenticated, are fully covered.
 *
 * <p>The counter is a fixed window rather than a sliding one: it permits a 2Ã— burst across a
 * window boundary, which is an acceptable trade for one Redis {@code INCR} per request on the hot
 * path. Tighten it only if abuse actually exploits the boundary.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

  private static final String PREFIX = "ratelimit:";
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final StringRedisTemplate redis;
  private final int authenticatedPerMinute;
  private final int anonymousPerMinute;

  public RateLimitInterceptor(
      StringRedisTemplate redis, int authenticatedPerMinute, int anonymousPerMinute) {
    this.redis = redis;
    this.authenticatedPerMinute = authenticatedPerMinute;
    this.anonymousPerMinute = anonymousPerMinute;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    boolean authenticated = auth != null && auth.isAuthenticated() && !isAnonymous(auth);

    String key = PREFIX + (authenticated ? "u:" + auth.getName() : "ip:" + clientIp(request));
    int limit = authenticated ? authenticatedPerMinute : anonymousPerMinute;

    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redis.expire(key, WINDOW);
    }
    if (count != null && count > limit) {
      response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
      throw new RateLimitException("error.rate_limited");
    }
    return true;
  }

  private static boolean isAnonymous(Authentication auth) {
    return "anonymousUser".equals(auth.getPrincipal());
  }

  /**
   * Trusts {@code X-Forwarded-For} only because the app already runs behind a proxy that sets it
   * ({@code server.forward-headers-strategy: framework}). If that stops being true this header
   * becomes client-controlled and the limit becomes trivially bypassable.
   */
  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
  }
}
