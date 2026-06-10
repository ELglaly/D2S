package com.schoolbridge.api.assistant.tools.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.assistant.tools.support.NameMatching.MatchResult;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class NameMatchingTest {

  private static final Function<String, String> ID = Function.identity();

  @Test
  void exactMatchBeatsSubstring() {
    MatchResult<String> r = NameMatching.match("Ali", List.of("Ali", "Ali Hassan"), ID);
    assertThat(r.unique()).isTrue();
    assertThat(r.first()).isEqualTo("Ali");
  }

  @Test
  void caseInsensitiveAndTrimmed() {
    MatchResult<String> r = NameMatching.match("  ali  ", List.of("ALI"), ID);
    assertThat(r.unique()).isTrue();
  }

  @Test
  void substringMatchesWhenNoExact() {
    MatchResult<String> r = NameMatching.match("Hass", List.of("Ali Hassan", "Mona"), ID);
    assertThat(r.unique()).isTrue();
    assertThat(r.first()).isEqualTo("Ali Hassan");
  }

  @Test
  void noMatchIsEmpty() {
    MatchResult<String> r = NameMatching.match("Zzz", List.of("Ali", "Mona"), ID);
    assertThat(r.none()).isTrue();
  }

  @Test
  void multipleSubstringMatchesAreAmbiguous() {
    MatchResult<String> r = NameMatching.match("Ahmed", List.of("Ahmed Ali", "Ahmed Saleh"), ID);
    assertThat(r.ambiguous()).isTrue();
    assertThat(r.matches()).hasSize(2);
  }

  @Test
  void blankQueryMatchesNothing() {
    assertThat(NameMatching.match("", List.of("Ali"), ID).none()).isTrue();
    assertThat(NameMatching.match("   ", List.of("Ali"), ID).none()).isTrue();
  }
}
