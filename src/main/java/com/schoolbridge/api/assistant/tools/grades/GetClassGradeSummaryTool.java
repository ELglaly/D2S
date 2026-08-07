package com.schoolbridge.api.assistant.tools.grades;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.grades.GradeService;
import com.schoolbridge.api.grades.dto.GradeRecordResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * TEACHER/ADMIN — per-subject grade summary (count + average score) for a class. Aggregates the
 * same rows {@code get_class_grades} returns, so the model can answer "how did 7A do in Math"
 * without reasoning over every grade.
 */
@Component
public class GetClassGradeSummaryTool implements ReadTool {

  private static final int MAX = 500;

  private final ToolSupport support;
  private final GradeService grades;

  public GetClassGradeSummaryTool(ToolSupport support, GradeService grades) {
    this.support = support;
    this.grades = grades;
  }

  @Override
  public String name() {
    return "get_class_grade_summary";
  }

  @Override
  public String description() {
    return "Summarize a class's grades per subject: how many were recorded and the average score.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().str("classRef", "Class name", true).build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.GRADE_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = support.clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clazz.result();
    }
    UUID classId = clazz.value().id();
    if (!support.teachesOrAdmin(ctx, classId)) {
      return support.denied("assistant.denied.class");
    }
    List<GradeRecordResponse> all =
        grades.listByClass(classId, PageRequest.of(0, MAX)).getContent();

    Map<String, List<GradeRecordResponse>> bySubject =
        all.stream()
            .collect(
                Collectors.groupingBy(
                    g -> g.subject() == null ? "" : g.subject(),
                    LinkedHashMap::new,
                    Collectors.toList()));

    List<Map<String, Object>> summary = new ArrayList<>();
    for (Map.Entry<String, List<GradeRecordResponse>> entry : bySubject.entrySet()) {
      DoubleSummaryStatistics stats =
          entry.getValue().stream()
              .map(GradeRecordResponse::score)
              .filter(Objects::nonNull)
              .mapToDouble(BigDecimal::doubleValue)
              .summaryStatistics();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("subject", entry.getKey());
      row.put("count", entry.getValue().size());
      row.put(
          "average", stats.getCount() == 0 ? null : Math.round(stats.getAverage() * 10.0) / 10.0);
      summary.add(row);
    }
    return ToolResult.ok(summary);
  }
}
