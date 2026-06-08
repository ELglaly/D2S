package com.schoolbridge.api.integrations;

import com.schoolbridge.api.integrations.sms.SmsClient;
import com.schoolbridge.api.integrations.whatsapp.MessageSendResult;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Orchestrator that prefers WhatsApp and falls back to SMS after a per-recipient threshold of
 * recent failures (default 2 inside a 10-minute window). The window is tracked in Redis with INCR +
 * TTL keyed by SHA-256 of the destination phone so we never expose the plaintext number in keys.
 *
 * <p>The Resilience4j circuit breaker on the WhatsApp adapter is treated as a failure-equivalent
 * (it throws {@code CallNotPermittedException} when open, caught here). After {@code
 * waitDurationInOpenState} the CB transitions to half-open and successful calls clear the
 * per-recipient counter, restoring WhatsApp as the primary channel.
 */
@Component
public class NotificationDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
  private static final String FAIL_KEY_PREFIX = "dispatcher:wa:fails:";

  private final WhatsAppClient whatsAppClient;
  private final SmsClient smsClient;
  private final StringRedisTemplate redis;
  private final MeterRegistry meterRegistry;
  private final int failureThreshold;
  private final Duration failureWindow;

  public NotificationDispatcher(
      WhatsAppClient whatsAppClient,
      SmsClient smsClient,
      StringRedisTemplate redis,
      MeterRegistry meterRegistry,
      @Value("${schoolbridge.notification.fallback.whatsapp-failure-threshold:2}")
          int failureThreshold,
      @Value("${schoolbridge.notification.fallback.whatsapp-failure-window:10m}")
          Duration failureWindow) {
    this.whatsAppClient = whatsAppClient;
    this.smsClient = smsClient;
    this.redis = redis;
    this.meterRegistry = meterRegistry;
    this.failureThreshold = failureThreshold;
    this.failureWindow = failureWindow;
  }

  public DispatchResult dispatch(DispatchRequest request) {
    String phoneHash = phoneHash(request.recipientPhone());

    if (overFailureThreshold(phoneHash)) {
      log.info(
          "dispatcher_skipping_whatsapp phone={} reason=threshold_reached",
          maskPhone(request.recipientPhone()));
      return sendViaSms(request);
    }

    try {
      MessageSendResult result =
          whatsAppClient.sendTemplate(
              request.templateName(),
              request.recipientPhone(),
              request.language(),
              request.templateParams());
      if (!result.accepted()) {
        recordFailure(phoneHash);
        meterRegistry.counter("whatsapp.send.failure").increment();
        return sendViaSms(request);
      }
      clearFailures(phoneHash);
      meterRegistry.counter("whatsapp.send.success").increment();
      return new DispatchResult(NotificationChannel.WHATSAPP, result.messageId(), true);
    } catch (RuntimeException ex) {
      recordFailure(phoneHash);
      meterRegistry.counter("whatsapp.send.failure").increment();
      log.warn(
          "whatsapp_dispatch_failed phone={} cause={}",
          maskPhone(request.recipientPhone()),
          ex.getClass().getSimpleName() + ": " + ex.getMessage());
      return sendViaSms(request);
    }
  }

  private DispatchResult sendViaSms(DispatchRequest request) {
    try {
      MessageSendResult result =
          smsClient.send(request.recipientPhone(), request.smsBody(), request.language());
      meterRegistry.counter("notification.fallback.sms").increment();
      return new DispatchResult(NotificationChannel.SMS, result.messageId(), result.accepted());
    } catch (RuntimeException ex) {
      log.warn(
          "sms_dispatch_failed phone={} cause={}",
          maskPhone(request.recipientPhone()),
          ex.getClass().getSimpleName() + ": " + ex.getMessage());
      return new DispatchResult(NotificationChannel.SMS, null, false);
    }
  }

  private boolean overFailureThreshold(String phoneHash) {
    String value = redis.opsForValue().get(FAIL_KEY_PREFIX + phoneHash);
    if (value == null) {
      return false;
    }
    try {
      return Long.parseLong(value) >= failureThreshold;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private void recordFailure(String phoneHash) {
    String key = FAIL_KEY_PREFIX + phoneHash;
    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redis.expire(key, failureWindow);
    }
  }

  private void clearFailures(String phoneHash) {
    redis.delete(FAIL_KEY_PREFIX + phoneHash);
  }

  private static String phoneHash(String phone) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] bytes = md.digest((phone == null ? "" : phone).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String maskPhone(String phone) {
    if (phone == null || phone.length() <= 4) {
      return "***";
    }
    return "***" + phone.substring(phone.length() - 4);
  }
}
