package com.schoolbridge.api.subjects.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignSubjectToClassRequest(@NotNull UUID subjectId) {}
