package com.schoolbridge.api.classes.dto;

import com.schoolbridge.api.classes.RelationshipType;
import com.schoolbridge.api.classes.StudentStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ParentChildResponse(
    UUID studentId,
    String fullName,
    LocalDate dateOfBirth,
    StudentStatus status,
    RelationshipType relationship,
    boolean primaryContact,
    List<ChildClassSummary> classes) {}

