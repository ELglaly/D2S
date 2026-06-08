package com.schoolbridge.api.integrations.whatsapp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.common.error.IntegrationException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Pure (non-Spring) unit test for the Meta Cloud adapter's wire shape and exception mapping.
 * WireMock stands in for graph.facebook.com so we exercise the real RestClient round-trip without
 * network.
 *
 * <p>Resilience4j AOP is intentionally NOT in play here — that is exercised by {@link
 * MetaCloudWhatsAppClientResilienceTest}. This test focuses on:
 *
 * <ul>
 *   <li>Successful template send returns the provider {@code messages[0].id}.
 *   <li>4xx (e.g. invalid template name, bad token) → {@link IntegrationException}.
 *   <li>5xx → {@link WhatsAppRetryableException} (so Resilience4j retry config can pick it up).
 *   <li>Request body is shaped per Meta Cloud API v20.0.
 * </ul>
 */
class MetaCloudWhatsAppClientWireMockTest {

  private WireMockServer wireMock;
  private MetaCloudWhatsAppClient client;
  private static final String PHONE_NUMBER_ID = "999999999999999";
  private static final String SEND_PATH = "/" + PHONE_NUMBER_ID + "/messages";

  @BeforeEach
  void setUp() {
    wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMock.start();

    WhatsAppProperties properties = new WhatsAppProperties();
    properties.setApiBaseUrl(wireMock.baseUrl());
    properties.setPhoneNumberId(PHONE_NUMBER_ID);
    properties.setAccessToken("test-token");

    client = new MetaCloudWhatsAppClient(RestClient.builder(), properties);
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  @Test
  void sendTemplate_on200_returnsMessageId() {
    wireMock.stubFor(
        post(urlEqualTo(SEND_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"messaging_product\":\"whatsapp\",\"contacts\":[],\"messages\":[{\"id\":\"wamid.ABC\"}]}")));

    MessageSendResult result =
        client.sendTemplate(
            "parent_otp_v1", "+201234567890", Language.AR, List.of(TemplateParam.of("123456")));

    assertThat(result.accepted()).isTrue();
    assertThat(result.messageId()).isEqualTo("wamid.ABC");

    wireMock.verify(1, postRequestedFor(urlEqualTo(SEND_PATH)));
    var requests = wireMock.findAll(postRequestedFor(urlEqualTo(SEND_PATH)));
    assertThat(requests.get(0).getHeader("Authorization")).isEqualTo("Bearer test-token");
    assertThat(requests).hasSize(1);
    String body = requests.get(0).getBodyAsString();
    // shape: messaging_product, to, type=template,
    // template.{name,language.code=ar,components[0].parameters[0].text=123456}
    assertThat(body).contains("\"messaging_product\":\"whatsapp\"");
    assertThat(body).contains("\"to\":\"+201234567890\"");
    assertThat(body).contains("\"type\":\"template\"");
    assertThat(body).contains("\"name\":\"parent_otp_v1\"");
    assertThat(body).contains("\"code\":\"ar\"");
    assertThat(body).contains("\"text\":\"123456\"");
  }

  @Test
  void sendTemplate_on400_throwsIntegrationException() {
    wireMock.stubFor(
        post(urlEqualTo(SEND_PATH))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"error\":{\"message\":\"(#132012) Parameter format mismatch\",\"code\":132012}}")));

    assertThatThrownBy(
            () ->
                client.sendTemplate(
                    "bad_template", "+201234567890", Language.EN, List.of(TemplateParam.of("x"))))
        .isInstanceOf(IntegrationException.class);
  }

  @Test
  void sendTemplate_on5xx_throwsWhatsAppRetryableException() {
    wireMock.stubFor(
        post(urlEqualTo(SEND_PATH))
            .willReturn(aResponse().withStatus(502).withBody("Bad Gateway")));

    assertThatThrownBy(
            () ->
                client.sendTemplate(
                    "parent_otp_v1",
                    "+201234567890",
                    Language.AR,
                    List.of(TemplateParam.of("999999"))))
        .isInstanceOf(WhatsAppRetryableException.class);
  }

  @Test
  void sendTemplate_on200_butEmptyMessages_throwsIntegrationException() {
    wireMock.stubFor(
        post(urlEqualTo(SEND_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"messaging_product\":\"whatsapp\",\"contacts\":[]}")));

    assertThatThrownBy(
            () ->
                client.sendTemplate(
                    "parent_otp_v1",
                    "+201234567890",
                    Language.AR,
                    List.of(TemplateParam.of("999999"))))
        .isInstanceOf(IntegrationException.class);
  }
}
