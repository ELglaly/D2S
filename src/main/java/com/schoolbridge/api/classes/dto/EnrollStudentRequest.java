package com.schoolbridge.api.classes.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record EnrollStudentRequest(@NotNull UUID studentId) {}

