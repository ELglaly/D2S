package com.schoolbridge.api.assistant.confirm;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic (non-LLM) yes/no classifier for confirmation text. The confirmation decision is
 * security-critical, so it must never depend on the model: a plain keyword match in Arabic/English
 * maps the user's reply to CONFIRM, CANCEL, or UNKNOWN.
 */
public final class ConfirmIntent {

  public enum Decision {
    CONFIRM,
    CANCEL,
    UNKNOWN
  }

  private static final Set<String> YES =
      Set.of(
          "yes", "y", "confirm", "ok", "okay", "sure", "Ù†Ø¹Ù…", "Ø£ÙƒØ¯", "Ø§ÙƒØ¯", "ØªØ£ÙƒÙŠØ¯", "ØªØ§ÙƒÙŠØ¯",
          "Ù…ÙˆØ§ÙÙ‚");
  private static final Set<String> NO =
      Set.of("no", "n", "cancel", "stop", "Ù„Ø§", "Ø¥Ù„ØºØ§Ø¡", "Ø§Ù„ØºØ§Ø¡", "Ø£Ù„ØºÙ", "Ø§Ù„Øº", "Ø±ÙØ¶");

  private ConfirmIntent() {}

  public static Decision classify(String text) {
    if (text == null) {
      return Decision.UNKNOWN;
    }
    String normalized = text.trim().toLowerCase(Locale.ROOT);
    if (YES.contains(normalized)) {
      return Decision.CONFIRM;
    }
    if (NO.contains(normalized)) {
      return Decision.CANCEL;
    }
    return Decision.UNKNOWN;
  }

  public static boolean isAffirmative(String text) {
    return classify(text) == Decision.CONFIRM;
  }
}

