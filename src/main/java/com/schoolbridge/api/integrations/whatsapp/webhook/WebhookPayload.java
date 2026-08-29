package com.schoolbridge.api.integrations.whatsapp.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Minimal Meta Cloud API webhook envelope â€” only the fields the M7 delivery-status consumer cares
 * about. {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps us forward-compatible if Meta
 * adds new fields (which they regularly do for messages/contacts/errors).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPayload(String object, List<Entry> entry) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Entry(String id, List<Change> changes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Change(String field, Value value) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Value(List<Status> statuses) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Status(String id, String status, String timestamp, String recipient_id) {}
}

