package com.schoolbridge.api.integrations.whatsapp;

/**
 * Internal marker for retryable WhatsApp failures (5xx, network errors). Resilience4j is configured
 * to retry on this type only â€” 4xx responses raise {@link
 * com.schoolbridge.api.common.error.IntegrationException} directly so they short-circuit retries
 * and trip the circuit breaker once the failure-rate threshold is hit.
 */
public class WhatsAppRetryableException extends RuntimeException {

  public WhatsAppRetryableException(String message) {
    super(message);
  }

  public WhatsAppRetryableException(String message, Throwable cause) {
    super(message, cause);
  }
}

