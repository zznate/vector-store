package io.github.zznate.vectorstore.metadata.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates the wire-level filter map into a {@link FilterExpr}. The
 * phase-1 grammar accepts only string values, which are treated as plain
 * equality tests; any object / array / {@code $}-prefixed value is a
 * phase-2 operator that is rejected here via
 * {@link UnsupportedFilterOperatorException}.
 *
 * <p>The exception type is intentionally plain-Java so the metadata module
 * stays free of API-layer coupling; the API translates it to the
 * {@code 400 unsupported_operator} envelope.
 */
public final class FilterParser {

  private FilterParser() {}

  /**
   * Parse a flat filter payload (as delivered by Jackson for a {@code
   * Map<String, Object>} DTO field). A {@code null} or empty map returns
   * {@code null}, which downstream consumers treat as "no filter" and
   * short-circuit to {@code Bits.ALL}.
   */
  public static FilterExpr parse(Map<String, ?> filter) {
    if (filter == null || filter.isEmpty()) {
      return null;
    }
    List<FilterExpr> terms = new ArrayList<>(filter.size());
    for (Map.Entry<String, ?> entry : filter.entrySet()) {
      terms.add(parseTerm(entry.getKey(), entry.getValue()));
    }
    return terms.size() == 1 ? terms.get(0) : new FilterExpr.And(terms);
  }

  private static FilterExpr parseTerm(String key, Object value) {
    if (value instanceof String s) {
      return new FilterExpr.Equals(key, s);
    }
    if (value instanceof Map<?, ?> m && !m.isEmpty()) {
      // MongoDB-style operator envelopes like {"$in": [...]} surface as a
      // nested map. Phase 1 supports none of them; report the first.
      Object firstKey = m.keySet().iterator().next();
      String operator = String.valueOf(firstKey);
      throw new UnsupportedFilterOperatorException(key, operator);
    }
    if (value == null) {
      throw new UnsupportedFilterOperatorException(key, "null");
    }
    // Numbers, booleans, arrays — all reserved for phase 2.
    throw new UnsupportedFilterOperatorException(key, value.getClass().getSimpleName());
  }
}
