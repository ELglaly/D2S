package com.schoolbridge.api.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Assigns/propagates a request id, exposes it on the response, and binds it to the logging MDC. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  private static final String MDC_KEY = "requestId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = request.getHeader(ApiConstants.REQUEST_ID_HEADER);
    if (!StringUtils.hasText(requestId)) {
      requestId = UUID.randomUUID().toString();
    }
    MDC.put(MDC_KEY, requestId);
    response.setHeader(ApiConstants.REQUEST_ID_HEADER, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
