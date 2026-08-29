package com.schoolbridge.api.common.web;

/** Shared API path prefixes and well-known header names. */
public final class ApiConstants {

  private ApiConstants() {}

  public static final String API_V1 = "/api/v1";
  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
}

