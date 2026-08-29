package com.schoolbridge.api.common.web;

import java.util.List;

/**
 * Uniform success envelope for all non-empty API responses.
 *
 * <ul>
 *   <li>Single resource: {@code { "data": {...}, "meta": null }}
 *   <li>Paginated list: {@code { "data": [...], "meta": { "page", "size", "totalElements",
 *       "totalPages" } }}
 *   <li>Error: RFC 7807 {@code ProblemDetail} â€” not wrapped, distinguished by the presence of a
 *       {@code "type"} URI field.
 *   <li>Empty (204): no body.
 * </ul>
 */
public record ApiResponse<T>(T data, PageMeta meta) {

  /** Wraps a single resource with no pagination metadata. */
  public static <T> ApiResponse<T> of(T data) {
    return new ApiResponse<>(data, null);
  }

  /**
   * Wraps a paged result: promotes the content list to {@code data} and extracts pagination fields
   * into {@code meta} so clients do not need a nested {@code content} key.
   */
  public static <T> ApiResponse<List<T>> paged(PageResponse<T> page) {
    PageMeta meta = new PageMeta(page.page(), page.size(), page.totalElements(), page.totalPages());
    return new ApiResponse<>(page.content(), meta);
  }
}

