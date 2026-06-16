package com.schoolbridge.api.assistant.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /conversations/{id}/messages}. {@code content} is the user's message, or a
 * CONFIRM/CANCEL reply ("yes"/"no"/"نعم"/"لا"/…) when answering a pending action proposal.
 */
public record SendMessageRequest(@NotBlank @Size(max = 4000) String content) {}
