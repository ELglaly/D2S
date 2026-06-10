package com.schoolbridge.api.assistant.tools.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolbridge.api.assistant.confirm.ConfirmationTokenService;
import com.schoolbridge.api.assistant.confirm.PendingActionStore;
import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.common.i18n.MessageResolver;
import org.springframework.stereotype.Component;

/**
 * The collaborators every action tool needs, bundled so {@code AbstractActionTool} takes one
 * constructor argument and subclasses add only their backing service(s).
 */
@Component
public class ActionSupport {

  private final PendingActionStore store;
  private final ConfirmationTokenService tokens;
  private final AssistantProperties properties;
  private final MessageResolver messages;
  private final ToolSupport tools;
  private final ObjectMapper mapper;

  public ActionSupport(
      PendingActionStore store,
      ConfirmationTokenService tokens,
      AssistantProperties properties,
      MessageResolver messages,
      ToolSupport tools,
      ObjectMapper mapper) {
    this.store = store;
    this.tokens = tokens;
    this.properties = properties;
    this.messages = messages;
    this.tools = tools;
    this.mapper = mapper;
  }

  public PendingActionStore store() {
    return store;
  }

  public ConfirmationTokenService tokens() {
    return tokens;
  }

  public AssistantProperties properties() {
    return properties;
  }

  public MessageResolver messages() {
    return messages;
  }

  public ToolSupport tools() {
    return tools;
  }

  public ObjectMapper mapper() {
    return mapper;
  }
}
