package com.schoolbridge.api.classes.dto;

import com.schoolbridge.api.classes.RelationshipType;
import java.time.Instant;
import java.util.UUID;

public record ParentStudentLinkResponse(
    UUID id,
    UUID schoolId,
    UUID parentUserId,
    UUID studentId,
    RelationshipType relationship,
    boolean primaryContact,
    Instant createdAt) {}

