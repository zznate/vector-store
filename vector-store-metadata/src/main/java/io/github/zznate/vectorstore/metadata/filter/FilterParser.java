package io.github.zznate.vectorstore.metadata.filter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates the wire-level filter map into a {@link FilterExpr}.
 *
 * <p>Recognised shapes:
 * <ul>
 *   <li>{@code {"key": "value"}} — equality leaf.
 *   <li>{@code {"key": {"$in": ["v1", "v2"]}}} — set-membership leaf.
 *   <li>{@code {"$or": [filterDoc, filterDoc, ...]}} — disjunction; must
 *       be the sole top-level key.
 *   <li>{@code {"$not": filterDoc, "key": "value", ...}} — unary
 *       negation, may sit alongside sibling equality keys.
 *   <li>Multiple sibling keys at the same level imply implicit AND.
 * </ul>
 *
 * <p>Top-level {@code $or} mixed with sibling keys raises
 * {@link AmbiguousFilterException} (mapped to {@code 400 bad_request}).
 * Any other operator — including range operators ({@code $gt}, {@code $lt},
 * {@code $gte}, {@code $lte}, {@code $between}) — raises
 * {@link UnsupportedFilterOperatorException} (mapped to
 * {@code 400 unsupported_operator}). Both exception types are plain Java
 * so the metadata module stays free of API-layer coupling.
 */
public final class FilterParser {

  private static final String OP_OR = "$or";
  private static final String OP_NOT = "$not";
  private static final String OP_IN = "$in";

  private FilterParser() {}

  /**
   * Parse a filter payload (as delivered by Jackson for a
   * {@code Map<String, Object>} DTO field). A {@code null} or empty map
   * returns {@code null}, which downstream consumers treat as "no filter"
   * and short-circuit to {@code Bits.ALL}.
   */
  public static FilterExpr parse(Map<String, ?> filter) {
    if (filter == null || filter.isEmpty()) {
      return null;
    }
    if (filter.containsKey(OP_OR)) {
      if (filter.size() > 1) {
        Set<String> siblings = new LinkedHashSet<>(filter.keySet());
        siblings.remove(OP_OR);
        throw new AmbiguousFilterException(OP_OR, siblings);
      }
      return parseOr(filter.get(OP_OR));
    }
    List<FilterExpr> terms = new ArrayList<>(filter.size());
    for (Map.Entry<String, ?> entry : filter.entrySet()) {
      terms.add(parseTopLevelEntry(entry.getKey(), entry.getValue()));
    }
    return terms.size() == 1 ? terms.get(0) : new FilterExpr.And(terms);
  }

  private static FilterExpr parseTopLevelEntry(String key, Object value) {
    if (OP_NOT.equals(key)) {
      return parseNot(value);
    }
    if (key.startsWith("$")) {
      throw new UnsupportedFilterOperatorException(key, key);
    }
    return parseLeaf(key, value);
  }

  private static FilterExpr parseLeaf(String key, Object value) {
    if (value instanceof String s) {
      return new FilterExpr.Equals(key, s);
    }
    if (value instanceof Map<?, ?> m && !m.isEmpty()) {
      return parseLeafOperator(key, m);
    }
    throw new UnsupportedFilterOperatorException(key, describeType(value));
  }

  private static FilterExpr parseLeafOperator(String key, Map<?, ?> envelope) {
    if (envelope.size() == 1 && envelope.containsKey(OP_IN)) {
      return parseIn(key, envelope.get(OP_IN));
    }
    Object firstKey = envelope.keySet().iterator().next();
    throw new UnsupportedFilterOperatorException(key, String.valueOf(firstKey));
  }

  private static FilterExpr.Or parseOr(Object orValue) {
    if (!(orValue instanceof List<?> list)) {
      throw new UnsupportedFilterOperatorException(OP_OR, describeType(orValue));
    }
    if (list.isEmpty()) {
      throw new UnsupportedFilterOperatorException(OP_OR, "empty_array");
    }
    List<FilterExpr> terms = new ArrayList<>(list.size());
    for (Object item : list) {
      terms.add(parseOrClause(item));
    }
    return new FilterExpr.Or(terms);
  }

  private static FilterExpr parseOrClause(Object item) {
    if (!(item instanceof Map<?, ?> m)) {
      throw new UnsupportedFilterOperatorException(OP_OR, describeType(item));
    }
    @SuppressWarnings("unchecked")
    Map<String, ?> typed = (Map<String, ?>) m;
    FilterExpr parsed = parse(typed);
    if (parsed == null) {
      throw new UnsupportedFilterOperatorException(OP_OR, "empty_clause");
    }
    return parsed;
  }

  private static FilterExpr.Not parseNot(Object notValue) {
    if (!(notValue instanceof Map<?, ?> m)) {
      throw new UnsupportedFilterOperatorException(OP_NOT, describeType(notValue));
    }
    @SuppressWarnings("unchecked")
    Map<String, ?> typed = (Map<String, ?>) m;
    FilterExpr inner = parse(typed);
    if (inner == null) {
      throw new UnsupportedFilterOperatorException(OP_NOT, "empty_clause");
    }
    return new FilterExpr.Not(inner);
  }

  private static FilterExpr.In parseIn(String key, Object inValue) {
    if (!(inValue instanceof List<?> list)) {
      throw new UnsupportedFilterOperatorException(key, OP_IN + ":" + describeType(inValue));
    }
    if (list.isEmpty()) {
      throw new UnsupportedFilterOperatorException(key, OP_IN + ":empty_array");
    }
    Set<String> values = new LinkedHashSet<>(list.size());
    for (Object item : list) {
      if (!(item instanceof String s)) {
        throw new UnsupportedFilterOperatorException(key, OP_IN + ":" + describeType(item));
      }
      values.add(s);
    }
    return new FilterExpr.In(key, values);
  }

  private static String describeType(Object value) {
    if (value == null) {
      return "null";
    }
    return value.getClass().getSimpleName();
  }
}
