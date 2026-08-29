package com.schoolbridge.api.common.error;

import com.schoolbridge.api.common.i18n.MessageResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into localized RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Highest-precedence so it wins over the framework's auto-registered ProblemDetail advice
 * (enabled by {@code spring.mvc.problemdetails.enabled=true}), which would otherwise map {@link
 * MethodArgumentNotValidException} to 400 instead of our chosen 422.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final MessageResolver messages;

  public GlobalExceptionHandler(MessageResolver messages) {
    this.messages = messages;
  }

  @ExceptionHandler(ApplicationException.class)
  public ProblemDetail handleApplication(ApplicationException ex, HttpServletRequest request) {
    ErrorType type = ex.errorType();
    ProblemDetail problem = base(type, request);
    problem.setDetail(messages.get(ex.messageKey(), ex.args()));

    if (type == ErrorType.TENANT_SECURITY) {
      // Cross-tenant attempts are security events; log loudly. TODO(audit): persist audit entry.
      log.warn("tenant_security_violation path={} traceId={}", request.getRequestURI(), traceId());
    } else if (type.status().is5xxServerError()) {
      log.error("application_error type={} path={}", type, request.getRequestURI(), ex);
    } else {
      log.debug(
          "application_error type={} path={} key={}",
          type,
          request.getRequestURI(),
          ex.messageKey());
    }
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleBeanValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    ProblemDetail problem = base(ErrorType.VALIDATION, request);
    problem.setDetail(messages.get(ErrorType.VALIDATION.defaultMessageKey()));
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(GlobalExceptionHandler::fieldError)
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    ProblemDetail problem = base(ErrorType.VALIDATION, request);
    problem.setDetail(messages.get(ErrorType.VALIDATION.defaultMessageKey()));
    List<Map<String, String>> errors =
        ex.getConstraintViolations().stream()
            .map(
                v ->
                    Map.of(
                        "field", v.getPropertyPath().toString(),
                        "message", v.getMessage()))
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
    ProblemDetail problem = base(ErrorType.AUTHORIZATION, request);
    problem.setDetail(messages.get(ErrorType.AUTHORIZATION.defaultMessageKey()));
    return problem;
  }

  @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
  public ProblemDetail handleSpringAuthentication(
      org.springframework.security.core.AuthenticationException ex, HttpServletRequest request) {
    ProblemDetail problem = base(ErrorType.AUTHENTICATION, request);
    problem.setDetail(messages.get(ErrorType.AUTHENTICATION.defaultMessageKey()));
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
    ProblemDetail problem = base(ErrorType.INTERNAL, request);
    problem.setDetail(messages.get(ErrorType.INTERNAL.defaultMessageKey()));
    log.error("unhandled_exception path={} traceId={}", request.getRequestURI(), traceId(), ex);
    return problem;
  }

  private ProblemDetail base(ErrorType type, HttpServletRequest request) {
    HttpStatus status = type.status();
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setTitle(messages.get(type.defaultMessageKey()));
    problem.setType(URI.create(type.typeUri()));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("traceId", traceId());
    return problem;
  }

  private static Map<String, String> fieldError(FieldError error) {
    String message = error.getDefaultMessage();
    return Map.of("field", error.getField(), "message", message == null ? "invalid" : message);
  }

  private static String traceId() {
    String traceId = MDC.get("traceId");
    return traceId == null ? "" : traceId;
  }
}

