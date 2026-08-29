package com.schoolbridge.api.assistant.tools.support;

import java.util.List;
import java.util.function.Function;

/** Case-insensitive name â†’ entity matching with exact-before-substring priority. */
public final class NameMatching {

  private NameMatching() {}

  public static <C> MatchResult<C> match(
      String query, List<C> candidates, Function<C, String> nameFn) {
    String q = normalize(query);
    if (q.isEmpty() || candidates.isEmpty()) {
      return new MatchResult<>(List.of());
    }
    List<C> exact = candidates.stream().filter(c -> normalize(nameFn.apply(c)).equals(q)).toList();
    if (!exact.isEmpty()) {
      return new MatchResult<>(exact);
    }
    List<C> contains =
        candidates.stream().filter(c -> normalize(nameFn.apply(c)).contains(q)).toList();
    return new MatchResult<>(contains);
  }

  static String normalize(String s) {
    // Arabic is caseless, so toLowerCase is a harmless no-op for it; trims surrounding whitespace.
    return s == null ? "" : s.trim().toLowerCase();
  }

  /** The candidates that matched a query. */
  public record MatchResult<C>(List<C> matches) {

    public boolean none() {
      return matches.isEmpty();
    }

    public boolean unique() {
      return matches.size() == 1;
    }

    public boolean ambiguous() {
      return matches.size() > 1;
    }

    public C first() {
      return matches.get(0);
    }
  }
}

