package com.schoolbridge.api.grades.dto;

import com.schoolbridge.api.grades.GradeRecord;
import org.springframework.stereotype.Component;

@Component
public class GradesMapper {

  public GradeRecordResponse toResponse(GradeRecord g) {
    return new GradeRecordResponse(
        g.getId(),
        g.getSchoolId(),
        g.getStudentId(),
        g.getClassId(),
        g.getSubject(),
        g.getPeriod(),
        g.getScore(),
        g.getGradeLabel(),
        g.getGradedByUserId(),
        g.getGradedAt(),
        g.getNotes(),
        g.getCreatedAt(),
        g.getUpdatedAt());
  }
}

