package com.schoolbridge.api.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /assistant/ask}. {@code language} is an optional hint ("ar"/"en"); when
 * omitted the language is detected from the question text, falling back to the request locale.
 */
public record AskRequest(
    @NotBlank @Size(max = 500) String question, @Size(max = 8) String language) {}

