package com.schoolbridge.api.assistant.tools;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Narrows a role's tool catalog to the domains the user's message plausibly touches, so a request
 * advertises a handful of tools instead of the whole catalog (the dominant input-token cost).
 * Biased for recall over precision: a query may select several domains, {@link ToolDomain#GENERAL}
 * tools are always offered, and when nothing matches it falls back to the full catalog â€” so
 * gating can trim tokens but never hide a tool the model genuinely needs without a safety net.
 *
 * <p>The {@code assistant.tool_gating} counter tracks the {@code gated} vs {@code fallback} split
 * so the fallback (misroute-prone) rate is observable before the keyword map is tightened.
 */
@Component
public class ToolSelector {

  private static final Logger log = LoggerFactory.getLogger(ToolSelector.class);

  private static final Map<ToolDomain, List<String>> KEYWORDS = buildKeywords();

  private final MeterRegistry meters;

  public ToolSelector(MeterRegistry meters) {
    this.meters = meters;
  }

  /**
   * The query-relevant subset of {@code tools} (already role-filtered and name-sorted). Returns the
   * input unchanged when no domain is detected, so callers always get a usable catalog.
   */
  public List<Tool> select(String query, List<Tool> tools) {
    if (tools.isEmpty()) {
      return tools;
    }
    EnumSet<ToolDomain> matched = detect(query);
    if (matched.isEmpty()) {
      meters.counter("assistant.tool_gating", "outcome", "fallback").increment();
      return tools;
    }
    List<Tool> gated =
        tools.stream()
            .filter(t -> t.domain() == ToolDomain.GENERAL || matched.contains(t.domain()))
            .toList();
    if (gated.isEmpty()) {
      meters.counter("assistant.tool_gating", "outcome", "fallback").increment();
      return tools;
    }
    meters.counter("assistant.tool_gating", "outcome", "gated").increment();
    log.debug("Tool gating: {} -> {} tools for domains {}", tools.size(), gated.size(), matched);
    return gated;
  }

  private EnumSet<ToolDomain> detect(String query) {
    EnumSet<ToolDomain> matched = EnumSet.noneOf(ToolDomain.class);
    if (query == null || query.isBlank()) {
      return matched;
    }
    String q = query.toLowerCase(Locale.ROOT);
    for (Map.Entry<ToolDomain, List<String>> entry : KEYWORDS.entrySet()) {
      for (String keyword : entry.getValue()) {
        if (q.contains(keyword)) {
          matched.add(entry.getKey());
          break;
        }
      }
    }
    return matched;
  }

  private static Map<ToolDomain, List<String>> buildKeywords() {
    Map<ToolDomain, List<String>> map = new EnumMap<>(ToolDomain.class);
    map.put(
        ToolDomain.ATTENDANCE,
        List.of("attend", "absent", "absence", "present", "حضور", "غياب", "غائب", "حاضر"));
    map.put(
        ToolDomain.GRADES,
        List.of("grade", "score", "mark", "result", "درجة", "درجات", "علامة", "نتيجة", "تقييم"));
    map.put(
        ToolDomain.HOMEWORK,
        List.of("homework", "assignment", "due", "submit", "واجب", "فرض", "تسليم"));
    map.put(
        ToolDomain.CLASSES,
        List.of("class", "classroom", "section", "enrol", "roster", "صف", "فصل", "شعبة", "تسجيل"));
    map.put(ToolDomain.SUBJECTS, List.of("subject", "course", "مادة", "مواد", "منهج"));
    map.put(
        ToolDomain.ANNOUNCEMENTS,
        List.of("announce", "announcement", "notice", "notify", "إعلان", "اعلان", "تنويه"));
    map.put(
        ToolDomain.PARENTS,
        List.of("parent", "guardian", "mother", "father", "ولي", "والد", "والدة", "ربط"));
    map.put(
        ToolDomain.STUDENTS,
        List.of("student", "child", "children", "pupil", "طالب", "طلاب", "تلميذ", "ابن", "طفل"));
    map.put(ToolDomain.STAFF, List.of("teacher", "staff", "معلم", "مدرس", "أستاذ"));
    return map;
  }
}
