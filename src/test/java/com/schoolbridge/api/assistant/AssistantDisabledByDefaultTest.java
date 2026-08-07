package com.schoolbridge.api.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolbridge.api.AbstractIntegrationTest;
import com.schoolbridge.api.assistant.llm.AssistantStartupValidator;
import com.schoolbridge.api.assistant.llm.DisabledLlmGateway;
import com.schoolbridge.api.assistant.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The assistant ships dark, and this is the test that keeps it that way (ADR-007).
 *
 * <p>Deliberately sets no properties: it boots the defaults exactly as a fresh deployment would, so
 * a future edit that flips {@code enabled} back to true in {@code application.yml} fails here
 * rather than in production. Before ADR-007 the comments claimed "ships dark" while the defaults
 * said {@code enabled=true} — a comment cannot fail a build, so this asserts the behaviour instead.
 */
@SpringBootTest
class AssistantDisabledByDefaultTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext context;

  @Test
  void noLlmIsWiredWithDefaultConfiguration() {
    // The only gateway is the no-op fallback — no provider client, no network, no key required.
    LlmGateway gateway = context.getBean(LlmGateway.class);
    assertThat(gateway).isInstanceOf(DisabledLlmGateway.class);

    // No Spring AI ChatModel at all: spring.ai.model.chat defaults to "none".
    assertThat(context.getBeanNamesForType(ChatModel.class)).isEmpty();

    // The key validator only loads when the assistant is switched on, so a disabled deployment
    // never needs a provider credential.
    assertThat(context.getBeanNamesForType(AssistantStartupValidator.class)).isEmpty();
  }
}
