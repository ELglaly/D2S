package com.schoolbridge.api.integrations;

import com.schoolbridge.api.identity.device.DeviceToken;
import com.schoolbridge.api.identity.device.DeviceTokenRepository;
import com.schoolbridge.api.integrations.push.PushNotificationClient;
import com.schoolbridge.api.integrations.push.PushSendResult;
import com.schoolbridge.api.integrations.sms.SmsClient;
import com.schoolbridge.api.integrations.whatsapp.MessageSendResult;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends one notification over the first channel that accepts it.
 *
 * <p>Two entry points, deliberately different:
 *
 * <ul>
 *   <li>{@link #dispatch(DispatchRequest)} â€” addressed to a bare phone number, WhatsApp with SMS
 *       fallback. This is the OTP path: no user, no preferences, no quiet hours, because a login
 *       code a user can mute is a support ticket rather than a preference.
 *   <li>{@link #dispatch(UserDispatchRequest, List)} â€” addressed to a known user over a caller-
 *       supplied channel order, which {@code NotificationPreferenceService} produced from that
 *       user's stored preference. Push is available here and only here, since it is addressed by
 *       device token.
 * </ul>
 *
 * <p>WhatsApp is demoted per recipient after a threshold of recent failures (default 2 inside a
 * 10-minute window), tracked in Redis with INCR + TTL keyed by SHA-256 of the destination phone so
 * the plaintext number never appears in a key. The Resilience4j circuit breaker on the WhatsApp
 * adapter is treated as failure-equivalent (it throws {@code CallNotPermittedException} when open,
 * caught here). After {@code waitDurationInOpenState} the breaker half-opens and a success clears
 * the per-recipient counter, restoring WhatsApp.
 *
 * <p>A channel with no way to reach the user â€” no active device token, no phone number â€” is
 * <em>unavailable</em>, not failed: the walk moves to the next channel without recording anything
 * against the recipient.
 */
@Component
public class NotificationDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
  private static final String FAIL_KEY_PREFIX = "dispatcher:wa:fails:";

  private final WhatsAppClient whatsAppClient;
  private final SmsClient smsClient;
  private final PushNotificationClient pushClient;
  private final DeviceTokenRepository deviceTokens;
  private final StringRedisTemplate redis;
  private final MeterRegistry meterRegistry;
  private final int failureThreshold;
  private final Duration failureWindow;

  public NotificationDispatcher(
      WhatsAppClient whatsAppClient,
      SmsClient smsClient,
      PushNotificationClient pushClient,
      DeviceTokenRepository deviceTokens,
      StringRedisTemplate redis,
      MeterRegistry meterRegistry,
      @Value("${schoolbridge.notification.fallback.whatsapp-failure-threshold:2}")
          int failureThreshold,
      @Value("${schoolbridge.notification.fallback.whatsapp-failure-window:10m}")
          Duration failureWindow) {
    this.whatsAppClient = whatsAppClient;
    this.smsClient = smsClient;
    this.pushClient = pushClient;
    this.deviceTokens = deviceTokens;
    this.redis = redis;
    this.meterRegistry = meterRegistry;
    this.failureThreshold = failureThreshold;
    this.failureWindow = failureWindow;
  }

  /** WhatsApp with SMS fallback, addressed to a phone number. Used by the OTP path. */
  public DispatchResult dispatch(DispatchRequest request) {
    DispatchResult whatsApp = attemptWhatsApp(request);
    if (whatsApp != null && whatsApp.accepted()) {
      return whatsApp;
    }
    return sendViaSms(request);
  }

  /**
   * Walks {@code channels} in order and returns the first accepted result. When no channel accepts,
   * returns a not-accepted result tagged with the last channel actually attempted, so the caller's
   * recipient row records where it got to rather than a channel that was never tried.
   */
  public DispatchResult dispatch(UserDispatchRequest request, List<NotificationChannel> channels) {
    NotificationChannel lastAttempted = null;
    for (NotificationChannel channel : channels) {
      DispatchResult result =
          switch (channel) {
            case PUSH -> attemptPush(request);
            case WHATSAPP -> attemptWhatsApp(request.content());
            case SMS -> attemptSms(request.content());
          };
      if (result == null) {
        continue; // Channel unavailable for this recipient â€” not a failure.
      }
      lastAttempted = channel;
      if (result.accepted()) {
        return result;
      }
    }
    return new DispatchResult(
        lastAttempted == null ? NotificationChannel.WHATSAPP : lastAttempted, null, false);
  }

  /**
   * @return null when push cannot reach this user at all (no content, or no active device); a
   *     not-accepted result when every registered device rejected the send.
   */
  private DispatchResult attemptPush(UserDispatchRequest request) {
    if (!request.hasPushContent()) {
      return null;
    }
    List<DeviceToken> devices = deviceTokens.findActiveByUserId(request.target().userId());
    if (devices.isEmpty()) {
      return null;
    }
    Map<String, String> data = request.pushData() == null ? new HashMap<>() : request.pushData();
    String acceptedMessageId = null;
    // Every registered device gets the notification -- a parent with a phone and a tablet expects
    // both to buzz. One acceptance is enough to call the notification delivered.
    for (DeviceToken device : devices) {
      try {
        PushSendResult result =
            pushClient.send(device.getFcmToken(), request.pushTitle(), request.pushBody(), data);
        if (result.accepted() && acceptedMessageId == null) {
          acceptedMessageId = result.messageId();
        }
      } catch (RuntimeException ex) {
        log.warn(
            "push_dispatch_failed userId={} cause={}",
            request.target().userId(),
            ex.getClass().getSimpleName() + ": " + ex.getMessage());
      }
    }
    if (acceptedMessageId == null) {
      meterRegistry.counter("push.send.failure").increment();
      return new DispatchResult(NotificationChannel.PUSH, null, false);
    }
    meterRegistry.counter("push.send.success").increment();
    return new DispatchResult(NotificationChannel.PUSH, acceptedMessageId, true);
  }

  /**
   * @return null when WhatsApp was not attempted at all â€” no phone, or the recipient is currently
   *     demoted by the failure counter.
   */
  private DispatchResult attemptWhatsApp(DispatchRequest request) {
    if (request.recipientPhone() == null || request.recipientPhone().isBlank()) {
      return null;
    }
    String phoneHash = phoneHash(request.recipientPhone());
    if (overFailureThreshold(phoneHash)) {
      log.info(
          "dispatcher_skipping_whatsapp phone={} reason=threshold_reached",
          maskPhone(request.recipientPhone()));
      return null;
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
        return new DispatchResult(NotificationChannel.WHATSAPP, null, false);
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
      return new DispatchResult(NotificationChannel.WHATSAPP, null, false);
    }
  }

  /**
   * @return null when there is no phone number to send to.
   */
  private DispatchResult attemptSms(DispatchRequest request) {
    if (request.recipientPhone() == null || request.recipientPhone().isBlank()) {
      return null;
    }
    return sendViaSms(request);
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

