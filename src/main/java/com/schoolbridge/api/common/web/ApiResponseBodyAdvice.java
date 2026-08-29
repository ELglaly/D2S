package com.schoolbridge.api.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every non-error, non-empty success response in {@link ApiResponse} so Flutter clients have
 * a single top-level shape: {@code { "data": T, "meta": PageMeta | null }}.
 *
 * <p>Skips:
 *
 * <ul>
 *   <li>{@link ProblemDetail} â€” errors keep their RFC 7807 shape.
 *   <li>Null bodies â€” 204 No Content responses stay empty.
 *   <li>Already-wrapped {@link ApiResponse} bodies â€” prevents double-wrapping.
 * </ul>
 *
 * <p>{@link PageResponse} bodies are unwrapped: their {@code content} list becomes {@code data} and
 * pagination fields move into {@code meta}.
 */
@RestControllerAdvice
public class ApiResponseBodyAdvice implements ResponseBodyAdvice<Object> {

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    // Only wrap when Jackson is writing the response (String/byte[] responses skip this).
    if (!MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType)) {
      return false;
    }
    // Only wrap our own application controllers, not Spring Boot Actuator or framework handlers.
    String declaringClass = returnType.getDeclaringClass().getName();
    return declaringClass.startsWith("com.schoolbridge.api");
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body == null) {
      return null;
    }
    if (body instanceof ProblemDetail) {
      return body;
    }
    if (body instanceof ApiResponse<?>) {
      return body;
    }
    if (body instanceof PageResponse<?> page) {
      return ApiResponse.paged(page);
    }
    return ApiResponse.of(body);
  }
}

