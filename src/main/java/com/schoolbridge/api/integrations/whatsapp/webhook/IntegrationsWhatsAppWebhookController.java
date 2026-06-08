package com.schoolbridge.api.integrations.whatsapp.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.announcements.AnnouncementRecipient;
import com.schoolbridge.api.announcements.enums.DeliveryStatus;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.common.error.AuthenticationException;
import com.schoolbridge.api.common.error.AuthorizationException;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Meta Cloud API webhook endpoint at {@code /integrations/whatsapp/webhook} (PUB; allowlisted in
 * {@link com.schoolbridge.api.common.security.SecurityConfig}).
 *
 * <p>GET = subscribe handshake (verify-token equality, 200 + challenge / 403). POST = delivery and
 * read receipts; the body is HMAC-SHA256-verified against {@code X-Hub-Signature-256} using {@code
 * WHATSAPP_APP_SECRET} (constant-time, on the raw bytes — see {@link WebhookSignatureVerifier}).
 *
 * <p>Idempotency: Meta does not supply an {@code Idempotency-Key}, so we use a dedicated Redis key
 * {@code idempotency:whatsapp:{messageId}:{status}} (TTL 24h) to drop duplicate deliveries —
 * tighter than the generic {@code IdempotencyFilter}.
 */
@RestController
@RequestMapping("/integrations/whatsapp/webhook")
@Tag(
    name = "WhatsApp Webhook",
    description =
        "Meta Cloud API webhook for delivery receipts and status updates. "
            + "No bearer token is required — requests are authenticated by HMAC-SHA256 signature.")
public class IntegrationsWhatsAppWebhookController {

  private static final Logger log =
      LoggerFactory.getLogger(IntegrationsWhatsAppWebhookController.class);
  private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:whatsapp:";
  private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

  private final WhatsAppProperties properties;
  private final WebhookSignatureVerifier signatureVerifier;
  private final AnnouncementRecipientRepository recipients;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  public IntegrationsWhatsAppWebhookController(
      WhatsAppProperties properties,
      WebhookSignatureVerifier signatureVerifier,
      AnnouncementRecipientRepository recipients,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.properties = properties;
    this.signatureVerifier = signatureVerifier;
    this.recipients = recipients;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
  }

  @GetMapping
  @SecurityRequirements
  @Operation(
      summary = "Webhook subscription verification (Meta handshake)",
      description =
          "Called by Meta when a webhook subscription is registered or refreshed. "
              + "Returns the hub.challenge value if hub.verify_token matches the configured secret.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Verification succeeded; challenge echoed"),
    @ApiResponse(
        responseCode = "403",
        description = "hub.mode is not 'subscribe' or verify_token mismatch")
  })
  public ResponseEntity<String> verifySubscribe(
      @Parameter(description = "Must be 'subscribe'")
          @RequestParam(name = "hub.mode", required = false)
          String mode,
      @Parameter(description = "Must match WHATSAPP_VERIFY_TOKEN")
          @RequestParam(name = "hub.verify_token", required = false)
          String token,
      @Parameter(description = "Echoed back on success")
          @RequestParam(name = "hub.challenge", required = false)
          String challenge) {
    if (!"subscribe".equals(mode)) {
      throw new AuthorizationException("error.whatsapp.webhook.invalid_verify_token");
    }
    String expected = properties.getVerifyToken();
    if (expected == null || token == null || !constantTimeEquals(expected, token)) {
      throw new AuthorizationException("error.whatsapp.webhook.invalid_verify_token");
    }
    return ResponseEntity.ok(challenge);
  }

  @PostMapping
  @SecurityRequirements
  @Transactional
  @Operation(
      summary = "Receive delivery status update",
      description =
          "Called by Meta for every message status transition (sent, delivered, read, failed). "
              + "The raw request body is verified against the X-Hub-Signature-256 HMAC header "
              + "before processing. Duplicate events are deduplicated via Redis (TTL 24h).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Event accepted (always returned to Meta to suppress retries)"),
    @ApiResponse(responseCode = "401", description = "X-Hub-Signature-256 missing or invalid")
  })
  public ResponseEntity<Void> receive(
      @RequestBody byte[] rawBody,
      @Parameter(
              description =
                  "HMAC-SHA256 signature: 'sha256=<hex>' computed over the raw body using WHATSAPP_APP_SECRET")
          @RequestHeader(value = "X-Hub-Signature-256", required = false)
          String signatureHeader) {
    meterRegistry.counter("whatsapp.webhook.received").increment();

    if (!signatureVerifier.matches(rawBody, signatureHeader)) {
      meterRegistry.counter("whatsapp.webhook.signature_invalid").increment();
      throw new AuthenticationException("error.whatsapp.webhook.invalid_signature");
    }

    WebhookPayload payload;
    try {
      payload = objectMapper.readValue(rawBody, WebhookPayload.class);
    } catch (Exception ex) {
      log.warn("whatsapp_webhook_unparseable bodyLen={}", rawBody.length, ex);
      return ResponseEntity.ok().build();
    }

    if (payload == null || payload.entry() == null) {
      return ResponseEntity.ok().build();
    }
    for (WebhookPayload.Entry entry : payload.entry()) {
      if (entry.changes() == null) continue;
      for (WebhookPayload.Change change : entry.changes()) {
        if (change.value() == null || change.value().statuses() == null) continue;
        for (WebhookPayload.Status status : change.value().statuses()) {
          processStatus(status);
        }
      }
    }
    return ResponseEntity.ok().build();
  }

  private void processStatus(WebhookPayload.Status status) {
    if (status.id() == null || status.status() == null) {
      return;
    }
    String dedupeKey = IDEMPOTENCY_KEY_PREFIX + status.id() + ":" + status.status();
    Boolean acquired = redis.opsForValue().setIfAbsent(dedupeKey, "1", IDEMPOTENCY_TTL);
    if (!Boolean.TRUE.equals(acquired)) {
      log.debug("whatsapp_webhook_duplicate messageId={} status={}", status.id(), status.status());
      return;
    }
    Optional<AnnouncementRecipient> recipient = recipients.findByMessageId(status.id());
    if (recipient.isEmpty()) {
      log.debug("whatsapp_webhook_no_recipient messageId={}", status.id());
      return;
    }
    DeliveryStatus newStatus = mapStatus(status.status());
    if (newStatus == null) {
      return;
    }
    AnnouncementRecipient row = recipient.get();
    if (!shouldAdvance(row.getDeliveryStatus(), newStatus)) {
      log.debug(
          "whatsapp_webhook_no_advance current={} incoming={}", row.getDeliveryStatus(), newStatus);
      return;
    }
    row.updateDeliveryStatus(newStatus);
  }

  /**
   * Don't downgrade a recipient: READ should not be overwritten by a late DELIVERED, etc. Meta
   * sometimes delivers status callbacks out of order on retries.
   */
  private static boolean shouldAdvance(DeliveryStatus current, DeliveryStatus incoming) {
    return rank(incoming) > rank(current);
  }

  private static int rank(DeliveryStatus status) {
    return switch (status) {
      case QUEUED -> 0;
      case SENT -> 1;
      case DELIVERED -> 2;
      case READ -> 3;
      case FAILED -> 4;
    };
  }

  private static DeliveryStatus mapStatus(String metaStatus) {
    return switch (metaStatus.toLowerCase(java.util.Locale.ROOT)) {
      case "sent" -> DeliveryStatus.SENT;
      case "delivered" -> DeliveryStatus.DELIVERED;
      case "read" -> DeliveryStatus.READ;
      case "failed" -> DeliveryStatus.FAILED;
      default -> null;
    };
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  // Unused but kept for SpotBugs: prevent "list field may be exposed" warnings when WebhookPayload
  // is refactored.
  @SuppressWarnings("unused")
  private static List<?> safeList(List<?> list) {
    return list == null ? List.of() : list;
  }
}
