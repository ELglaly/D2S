package com.schoolbridge.api.assistant.confirm;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.assistant.confirm.ConfirmIntent.Decision;
import org.junit.jupiter.api.Test;

class ConfirmIntentTest {

  @Test
  void recognizesAffirmativesInBothLanguages() {
    assertThat(ConfirmIntent.classify("yes")).isEqualTo(Decision.CONFIRM);
    assertThat(ConfirmIntent.classify("Confirm")).isEqualTo(Decision.CONFIRM);
    assertThat(ConfirmIntent.classify("نعم")).isEqualTo(Decision.CONFIRM);
    assertThat(ConfirmIntent.classify("تأكيد")).isEqualTo(Decision.CONFIRM);
  }

  @Test
  void recognizesNegativesInBothLanguages() {
    assertThat(ConfirmIntent.classify("no")).isEqualTo(Decision.CANCEL);
    assertThat(ConfirmIntent.classify("cancel")).isEqualTo(Decision.CANCEL);
    assertThat(ConfirmIntent.classify("لا")).isEqualTo(Decision.CANCEL);
    assertThat(ConfirmIntent.classify("إلغاء")).isEqualTo(Decision.CANCEL);
  }

  @Test
  void unknownForAmbiguousOrNull() {
    assertThat(ConfirmIntent.classify("maybe")).isEqualTo(Decision.UNKNOWN);
    assertThat(ConfirmIntent.classify(null)).isEqualTo(Decision.UNKNOWN);
    assertThat(ConfirmIntent.isAffirmative(null)).isFalse();
    assertThat(ConfirmIntent.isAffirmative("نعم")).isTrue();
  }
}
