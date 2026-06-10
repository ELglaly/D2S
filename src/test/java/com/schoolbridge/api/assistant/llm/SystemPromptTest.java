package com.schoolbridge.api.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemPromptTest {

  @Test
  void detectsArabicFromArabicLetters() {
    assertThat(SystemPrompt.detectLanguage("ابني كان غايب امبارح", Locale.ENGLISH).getLanguage())
        .isEqualTo("ar");
  }

  @Test
  void fallsBackToEnglishForLatinText() {
    assertThat(
            SystemPrompt.detectLanguage("was my son absent yesterday", Locale.ENGLISH)
                .getLanguage())
        .isEqualTo("en");
  }

  @Test
  void nullTextUsesFallback() {
    assertThat(SystemPrompt.detectLanguage(null, Locale.forLanguageTag("ar")).getLanguage())
        .isEqualTo("ar");
  }

  @Test
  void promptMentionsRoleAndLanguageAndConfirmGuardrail() {
    ToolContext ctx =
        new ToolContext(
            UUID.randomUUID(),
            new ParentPrincipal(UUID.randomUUID(), UUID.randomUUID()),
            UserRole.PARENT,
            Locale.forLanguageTag("ar"),
            null);

    String prompt = new SystemPrompt().build(ctx);

    assertThat(prompt).contains("parent").contains("Arabic").contains("NEVER perform it");
  }
}
