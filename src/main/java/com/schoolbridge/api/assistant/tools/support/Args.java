package com.schoolbridge.api.assistant.tools.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Null-safe accessors for LLM-supplied argument nodes. Returns {@code null} for absent/blank. */
public final class Args {

  private Args() {}

  public static String str(JsonNode args, String field) {
    JsonNode n = args == null ? null : args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    String v = n.asText().trim();
    return v.isEmpty() ? null : v;
  }

  public static LocalDate date(JsonNode args, String field) {
    String v = str(args, field);
    if (v == null) {
      return null;
    }
    try {
      return LocalDate.parse(v);
    } catch (DateTimeParseException ex) {
      return null;
    }
  }

  public static Integer integer(JsonNode args, String field) {
    JsonNode n = args == null ? null : args.get(field);
    return n == null || !n.isNumber() ? null : n.asInt();
  }

  public static Double number(JsonNode args, String field) {
    JsonNode n = args == null ? null : args.get(field);
    return n == null || !n.isNumber() ? null : n.asDouble();
  }

  public static boolean bool(JsonNode args, String field, boolean def) {
    JsonNode n = args == null ? null : args.get(field);
    return n == null || n.isNull() ? def : n.asBoolean(def);
  }
}
