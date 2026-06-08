package com.schoolbridge.api.integrations.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process push client used in tests. Captures sends for assertion; replaces {@link
 * LoggingPushClient} via {@code @Primary} when the "test" profile is active.
 */
@Component
@Profile("test")
@Primary
public class FakePushClient implements PushNotificationClient {

  public record Sent(String fcmToken, String title, String body, Map<String, String> data) {}

  private final List<Sent> sends = new ArrayList<>();

  @Override
  public PushSendResult send(String fcmToken, String title, String body, Map<String, String> data) {
    sends.add(new Sent(fcmToken, title, body, data));
    return new PushSendResult(true, "fake-msg-" + sends.size());
  }

  public List<Sent> getSends() {
    return List.copyOf(sends);
  }

  public void reset() {
    sends.clear();
  }
}
