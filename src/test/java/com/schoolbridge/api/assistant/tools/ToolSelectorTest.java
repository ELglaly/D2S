package com.schoolbridge.api.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.common.security.authz.Permission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolSelectorTest {

  private final ToolSelector selector = new ToolSelector(new SimpleMeterRegistry());

  private final Tool attendance = tool("get_attendance", ToolDomain.ATTENDANCE);
  private final Tool grades = tool("get_grades", ToolDomain.GRADES);
  private final Tool general = tool("whoami", ToolDomain.GENERAL);
  private final List<Tool> all = List.of(attendance, grades, general);

  @Test
  void keepsOnlyMatchedDomainPlusGeneral() {
    List<Tool> selected = selector.select("show me the attendance for 5A", all);

    assertThat(selected).containsExactly(attendance, general);
  }

  @Test
  void matchesMultipleDomains() {
    List<Tool> selected = selector.select("his attendance and his grades", all);

    assertThat(selected).containsExactly(attendance, grades, general);
  }

  @Test
  void detectsArabicKeywords() {
    List<Tool> selected = selector.select("كم مرة غياب", all);

    assertThat(selected).containsExactly(attendance, general);
  }

  @Test
  void fallsBackToFullCatalogWhenNoDomainMatches() {
    List<Tool> selected = selector.select("hello there", all);

    assertThat(selected).containsExactlyElementsOf(all);
  }

  @Test
  void emptyCatalogStaysEmpty() {
    assertThat(selector.select("attendance", List.of())).isEmpty();
  }

  private static Tool tool(String name, ToolDomain domain) {
    return new Tool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return name;
      }

      @Override
      public JsonNode inputSchema() {
        return Schema.empty();
      }

      @Override
      public ToolKind kind() {
        return ToolKind.READ;
      }

      @Override
      public Set<Permission> permissions() {
        return Set.of(Permission.STUDENT_READ);
      }

      @Override
      public ToolDomain domain() {
        return domain;
      }
    };
  }
}
