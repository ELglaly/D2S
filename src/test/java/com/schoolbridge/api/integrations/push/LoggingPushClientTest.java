package com.schoolbridge.api.integrations.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The dev stub must not claim to have delivered anything.
 *
 * <p>Push is first in {@code NotificationChannel.DEFAULT_ORDER} and the dispatcher stops at the
 * first channel that accepts. A stub returning {@code accepted = true} would therefore end the walk
 * and swallow every notification for any user with a registered device — in exactly the deployment
 * where FCM has not been configured yet, which is every deployment before someone remembers to set
 * {@code PUSH_FCM_ENABLED}. This is a one-line assertion guarding a silent total outage.
 */
class LoggingPushClientTest {

  @Test
  void reportsNotAccepted_soTheDispatcherFallsThroughToTheNextChannel() {
    PushSendResult result =
        new LoggingPushClient().send("token-abcd", "Title", "Body", Map.of("type", "homework"));

    assertThat(result.accepted()).isFalse();
    assertThat(result.messageId()).isNull();
  }
}
