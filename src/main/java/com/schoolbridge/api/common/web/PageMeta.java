package com.schoolbridge.api.common.web;

/** Pagination metadata included in {@link ApiResponse} when the payload is a page of results. */
public record PageMeta(int page, int size, long totalElements, int totalPages) {}

