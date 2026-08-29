package com.schoolbridge.api.common.security;

import com.schoolbridge.api.common.error.ErrorType;
import com.schoolbridge.api.common.i18n.MessageResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Returns a localized RFC 7807 401 response for unauthenticated requests to protected endpoints.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final MessageResolver messages;

  public RestAuthenticationEntryPoint(MessageResolver messages) {
    this.messages = messages;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String title = messages.get(ErrorType.AUTHENTICATION.defaultMessageKey());
    String traceId = MDC.get("traceId");
    String body =
        """
        {"type":"%s","title":"%s","status":401,"instance":"%s","traceId":"%s"}"""
            .formatted(
                ErrorType.AUTHENTICATION.typeUri(),
                escape(title),
                escape(request.getRequestURI()),
                traceId == null ? "" : traceId);
    response.getWriter().write(body);
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}

