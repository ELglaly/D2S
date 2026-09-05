package com.schoolbridge.api.integrations.whatsapp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.common.error.IntegrationException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Meta Cloud API v20.0 implementation of {@link WhatsAppClient}.
 *
 * <p>Wrapped in a Resilience4j circuit breaker + retry (configured in {@code application.yml} so
 * thresholds are tunable per environment). Only {@link WhatsAppRetryableException} (5xx / transport
 * faults) is retried â€” a 4xx is a permanent failure (bad template name, banned recipient, expired
 * token) and is mapped straight to {@link IntegrationException} so the dispatcher can flip the
 * recipient to the SMS fallback channel.
 *
 * <p>Active only when {@code schoolbridge.whatsapp.access-token} is set. In local/dev environments
 * without credentials, {@link LoggingWhatsAppClient} is used instead.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "schoolbridge.whatsapp.access-token")
public class MetaCloudWhatsAppClient implements WhatsAppClient {

  private static final Logger log = LoggerFactory.getLogger(MetaCloudWhatsAppClient.class);

  static final String CIRCUIT_BREAKER = "whatsapp";
  static final String RETRY = "whatsapp";

  private final RestClient restClient;
  private final WhatsAppProperties properties;

  public MetaCloudWhatsAppClient(RestClient.Builder builder, WhatsAppProperties properties) {
    this.properties = properties;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5_000);
    factory.setReadTimeout(15_000);
    this.restClient =
        builder
            .baseUrl(properties.getApiBaseUrl())
            .requestFactory(factory)
            .defaultHeader("Authorization", "Bearer " + nullSafe(properties.getAccessToken()))
            .build();
  }

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER)
  @Retry(name = RETRY)
  public MessageSendResult sendTemplate(
      String templateName, String recipientPhone, Language language, List<TemplateParam> params) {
    long startMillis = System.currentTimeMillis();
    SendRequest body = SendRequest.template(recipientPhone, templateName, language, params);
    try {
      SendResponse response =
          restClient
              .post()
              .uri("/{phoneNumberId}/messages", properties.getPhoneNumberId())
              .body(body)
              .retrieve()
              .body(SendResponse.class);
      String messageId = extractMessageId(response);
      log.info(
          "whatsapp_send_ok template={} phone={} latencyMs={} messageId={}",
          templateName,
          maskPhone(recipientPhone),
          System.currentTimeMillis() - startMillis,
          messageId);
      return MessageSendResult.accepted(messageId);
    } catch (HttpClientErrorException ex) {
      log.warn(
          "whatsapp_send_client_error template={} phone={} status={} body={}",
          templateName,
          maskPhone(recipientPhone),
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      throw new IntegrationException("error.whatsapp.unavailable", ex);
    } catch (HttpServerErrorException ex) {
      log.warn(
          "whatsapp_send_server_error template={} phone={} status={}",
          templateName,
          maskPhone(recipientPhone),
          ex.getStatusCode());
      throw new WhatsAppRetryableException("WhatsApp 5xx " + ex.getStatusCode().value(), ex);
    } catch (ResourceAccessException ex) {
      log.warn(
          "whatsapp_send_transport_error template={} phone={} cause={}",
          templateName,
          maskPhone(recipientPhone),
          ex.getMessage());
      throw new WhatsAppRetryableException("WhatsApp transport error", ex);
    } catch (org.springframework.web.client.RestClientResponseException ex) {
      HttpStatusCode status = ex.getStatusCode();
      if (status.is5xxServerError()) {
        throw new WhatsAppRetryableException("WhatsApp 5xx " + status.value(), ex);
      }
      throw new IntegrationException("error.whatsapp.unavailable", ex);
    }
  }

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER)
  @Retry(name = RETRY)
  public MessageSendResult sendText(String recipientPhone, String body) {
    long startMillis = System.currentTimeMillis();
    SendRequest payload = SendRequest.text(recipientPhone, body);
    try {
      SendResponse response =
          restClient
              .post()
              .uri("/{phoneNumberId}/messages", properties.getPhoneNumberId())
              .body(payload)
              .retrieve()
              .body(SendResponse.class);
      String messageId = extractMessageId(response);
      log.info(
          "whatsapp_send_text_ok phone={} latencyMs={} messageId={}",
          maskPhone(recipientPhone),
          System.currentTimeMillis() - startMillis,
          messageId);
      return MessageSendResult.accepted(messageId);
    } catch (HttpClientErrorException ex) {
      throw new IntegrationException("error.whatsapp.unavailable", ex);
    } catch (HttpServerErrorException ex) {
      throw new WhatsAppRetryableException("WhatsApp 5xx " + ex.getStatusCode().value(), ex);
    } catch (ResourceAccessException ex) {
      throw new WhatsAppRetryableException("WhatsApp transport error", ex);
    }
  }

  private static String extractMessageId(SendResponse response) {
    if (response == null || response.messages() == null || response.messages().isEmpty()) {
      throw new IntegrationException("error.whatsapp.unavailable");
    }
    String id = response.messages().get(0).id();
    if (id == null || id.isBlank()) {
      throw new IntegrationException("error.whatsapp.unavailable");
    }
    return id;
  }

  private static String maskPhone(String phone) {
    if (phone == null || phone.length() <= 4) {
      return "***";
    }
    return "***" + phone.substring(phone.length() - 4);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  /**
   * Meta Cloud API request envelope. We only build it from this class so the wire shape is in one
   * place; consumers of {@link WhatsAppClient} never see this DTO.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SendRequest(
      String messaging_product, String to, String type, Template template, TextBody text) {

    static SendRequest template(
        String to, String templateName, Language language, List<TemplateParam> params) {
      List<Map<String, Object>> parameters = new ArrayList<>();
      if (params != null) {
        for (TemplateParam p : params) {
          parameters.add(Map.of("type", "text", "text", p.text()));
        }
      }
      List<Map<String, Object>> components =
          parameters.isEmpty()
              ? List.of()
              : List.of(Map.of("type", "body", "parameters", parameters));
      Template tpl =
          new Template(templateName, new TemplateLanguage(toMetaLanguage(language)), components);
      return new SendRequest("whatsapp", to, "template", tpl, null);
    }

    static SendRequest text(String to, String body) {
      return new SendRequest("whatsapp", to, "text", null, new TextBody(body, false));
    }

    private static String toMetaLanguage(Language language) {
      return language == Language.AR ? "ar" : "en";
    }
  }

  record Template(String name, TemplateLanguage language, List<Map<String, Object>> components) {}

  record TemplateLanguage(String code) {}

  record TextBody(String body, boolean preview_url) {}

  record SendResponse(List<Message> messages) {

    record Message(String id) {}
  }
}
