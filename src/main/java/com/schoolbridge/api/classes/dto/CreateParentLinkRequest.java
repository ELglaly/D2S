package com.schoolbridge.api.classes.dto;

import com.schoolbridge.api.classes.RelationshipType;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateParentLinkRequest(
    @NotNull UUID parentUserId,
    @NotNull UUID studentId,
    @NotNull RelationshipType relationship,
    boolean primaryContact) {}
