package com.schoolbridge.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/**
 * Unit tests for {@link ApiResponseBodyAdvice} wrapping logic (beforeBodyWrite only; supports() is
 * covered implicitly by the integration tests).
 */
class ApiResponseBodyAdviceTest {

  private final ApiResponseBodyAdvice advice = new ApiResponseBodyAdvice();

  @Test
  void null_body_passesThrough() {
    Object result = advice.beforeBodyWrite(null, null, null, null, null, null);
    assertThat(result).isNull();
  }

  @Test
  void problemDetail_passesThrough_unwrapped() {
    ProblemDetail problem = ProblemDetail.forStatus(400);
    Object result = advice.beforeBodyWrite(problem, null, null, null, null, null);
    assertThat(result).isSameAs(problem);
  }

  @Test
  void alreadyWrapped_apiResponse_passesThrough() {
    ApiResponse<String> wrapped = ApiResponse.of("hello");
    Object result = advice.beforeBodyWrite(wrapped, null, null, null, null, null);
    assertThat(result).isSameAs(wrapped);
  }

  @Test
  void plainDto_getsWrappedInDataKey() {
    record Dto(String name) {}
    Dto dto = new Dto("test");
    Object result = advice.beforeBodyWrite(dto, null, null, null, null, null);
    assertThat(result).isInstanceOf(ApiResponse.class);
    ApiResponse<?> response = (ApiResponse<?>) result;
    assertThat(response.data()).isEqualTo(dto);
    assertThat(response.meta()).isNull();
  }

  @Test
  void pageResponse_isUnpackedIntoDataAndMeta() {
    var page = new PageResponse<>(java.util.List.of("a", "b"), 0, 10, 2L, 1);
    Object result = advice.beforeBodyWrite(page, null, null, null, null, null);
    assertThat(result).isInstanceOf(ApiResponse.class);
    ApiResponse<?> response = (ApiResponse<?>) result;
    assertThat(response.data()).isEqualTo(java.util.List.of("a", "b"));
    assertThat(response.meta()).isNotNull();
    assertThat(response.meta().totalElements()).isEqualTo(2L);
    assertThat(response.meta().totalPages()).isEqualTo(1);
  }
}
