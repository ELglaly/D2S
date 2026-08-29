package com.schoolbridge.api.common.idempotency;

import com.schoolbridge.api.common.error.ErrorType;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.common.web.ApiConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Enforces idempotency on state-changing {@code /api/v1} requests that carry an {@code
 * Idempotency-Key}. A previously completed request replays its cached response; a concurrent
 * duplicate is rejected with 409. Responses are cached only for non-5xx outcomes so failures can be
 * safely retried.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class IdempotencyFilter extends OncePerRequestFilter {

  private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final IdempotencyService idempotency;
  private final MessageResolver messages;

  public IdempotencyFilter(IdempotencyService idempotency, MessageResolver messages) {
    this.idempotency = idempotency;
    this.messages = messages;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!MUTATING.contains(request.getMethod())) {
      return true;
    }
    if (!request.getRequestURI().startsWith(ApiConstants.API_V1)) {
      return true;
    }
    return !StringUtils.hasText(request.getHeader(ApiConstants.IDEMPOTENCY_KEY_HEADER));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String key = buildKey(request);

    var cached = idempotency.find(key);
    if (cached.isPresent()) {
      writeCached(response, cached.get());
      return;
    }

    if (!idempotency.tryAcquire(key)) {
      writeProblem(response, HttpStatus.CONFLICT, "error.idempotency_in_progress", request);
      return;
    }

    boolean stored = false;
    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    try {
      filterChain.doFilter(request, wrapper);
      if (wrapper.getStatus() < 500) {
        idempotency.store(key, wrapper.getStatus(), wrapper.getContentAsByteArray());
        stored = true;
      }
      wrapper.copyBodyToResponse();
    } finally {
      if (!stored) {
        idempotency.release(key);
      }
    }
  }

  private static String buildKey(HttpServletRequest request) {
    String tenant = TenantContext.get().map(Object::toString).orElse("anon");
    String idemKey = request.getHeader(ApiConstants.IDEMPOTENCY_KEY_HEADER);
    String raw = tenant + ':' + request.getMethod() + ':' + request.getRequestURI() + ':' + idemKey;
    return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
  }

  private void writeCached(HttpServletResponse response, IdempotencyService.CachedResponse cached)
      throws IOException {
    response.setStatus(cached.status());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader("Idempotent-Replay", "true");
    response.getOutputStream().write(cached.body());
  }

  private void writeProblem(
      HttpServletResponse response,
      HttpStatus status,
      String messageKey,
      HttpServletRequest request)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String body =
        """
        {"type":"%s","title":"%s","status":%d,"instance":"%s"}"""
            .formatted(
                ErrorType.CONFLICT.typeUri(),
                messages.get(messageKey).replace("\"", "\\\""),
                status.value(),
                request.getRequestURI());
    response.getWriter().write(body);
  }
}

