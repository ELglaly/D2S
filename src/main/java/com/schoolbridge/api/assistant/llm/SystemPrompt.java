package com.schoolbridge.api.assistant.llm;

import com.schoolbridge.api.assistant.tools.ToolContext;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Builds the system prompt for a request: the caller's role, the answer language, and the hard
 * guardrails — most importantly that the model may <em>propose</em> actions but can never execute
 * them (the server owns the confirm gate) and must refer to people and classes by name, never id.
 */
@Component
public class SystemPrompt {

  private static final String PERSONA_TEMPLATE =
      "You are SchoolBridge's assistant, helping a {role} in a school's mobile app.";

  private static final String GUARDRAILS_TEMPLATE =
      """
      Rules:
      - Reply in {language}, matching the user's wording and tone.
      - Use ONLY the provided tools to read or change data. Never invent students, grades, \
      attendance, classes, or any other facts.
      - You may PROPOSE an action, but you can NEVER perform it yourself. The server shows the \
      user a confirmation and only then runs it. Do not claim an action is done unless a tool \
      result says so.
      - Refer to people, classes, and subjects by their names. You never see or handle internal \
      identifiers.
      - If a name is missing or matches more than one person, ask one short clarifying question \
      instead of guessing.
      - If the user asks for something this role is not allowed to do, say so briefly and, when \
      useful, mention who can do it.
      - Keep answers concise and specific to what the tools returned.
      """;

  /** The built-in default persona (used when a tenant has not set a custom system prompt). */
  public String defaultPersona(ToolContext ctx) {
    return PERSONA_TEMPLATE.replace("{role}", roleLabel(ctx));
  }

  /**
   * The immutable, security-critical guardrails. Always appended after the (possibly tenant-edited)
   * persona so an edited prompt can never weaken the confirm gate or leak internal identifiers.
   */
  public String guardrails(ToolContext ctx) {
    String language = "ar".equals(ctx.language().getLanguage()) ? "Arabic" : "English";
    return GUARDRAILS_TEMPLATE.replace("{language}", language);
  }

  /**
   * Composes the default persona with the guardrails — the prompt used by the one-shot {@code
   * /ask}.
   */
  public String build(ToolContext ctx) {
    return defaultPersona(ctx) + "\n\n" + guardrails(ctx);
  }

  private String roleLabel(ToolContext ctx) {
    return switch (ctx.role()) {
      case PARENT -> "parent";
      case TEACHER -> "teacher";
      case SCHOOL_ADMIN -> "school administrator";
      case SUPER_ADMIN -> "platform administrator";
    };
  }

  /**
   * Detect the answer language from free text: Arabic if it contains Arabic letters, else English.
   */
  public static Locale detectLanguage(String text, Locale fallback) {
    if (text == null) {
      return fallback;
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.UnicodeBlock.of(text.charAt(i)) == Character.UnicodeBlock.ARABIC) {
        return Locale.forLanguageTag("ar");
      }
    }
    return fallback;
  }
}
