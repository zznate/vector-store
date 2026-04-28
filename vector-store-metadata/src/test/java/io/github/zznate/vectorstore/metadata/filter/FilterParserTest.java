package io.github.zznate.vectorstore.metadata.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FilterParserTest {

  @Test
  void nullFilterReturnsNull() {
    assertThat(FilterParser.parse(null)).isNull();
  }

  @Test
  void emptyFilterReturnsNull() {
    assertThat(FilterParser.parse(Map.of())).isNull();
  }

  @Test
  void singleStringEntryProducesEquals() {
    FilterExpr expr = FilterParser.parse(Map.of("category", "shoes"));
    assertThat(expr).isEqualTo(new FilterExpr.Equals("category", "shoes"));
    assertThat(expr.termCount()).isEqualTo(1);
  }

  @Test
  void multipleStringEntriesProduceAndOfEquals() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("category", "shoes");
    raw.put("region", "us-west");

    FilterExpr expr = FilterParser.parse(raw);
    assertThat(expr).isInstanceOf(FilterExpr.And.class);
    List<FilterExpr> terms = ((FilterExpr.And) expr).terms();
    assertThat(terms)
        .containsExactly(
            new FilterExpr.Equals("category", "shoes"),
            new FilterExpr.Equals("region", "us-west"));
    assertThat(expr.termCount()).isEqualTo(2);
  }

  @Test
  void inLeafProducesInWithDistinctValues() {
    Map<String, Object> raw = Map.of("category", Map.of("$in", List.of("a", "b", "a")));
    FilterExpr expr = FilterParser.parse(raw);
    assertThat(expr).isInstanceOf(FilterExpr.In.class);
    FilterExpr.In in = (FilterExpr.In) expr;
    assertThat(in.key()).isEqualTo("category");
    assertThat(in.values()).containsExactlyInAnyOrder("a", "b");
    assertThat(expr.termCount()).isEqualTo(1);
  }

  @Test
  void inLeafMayMixWithSiblingEqualityKeys() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("category", Map.of("$in", List.of("a", "b")));
    raw.put("region", "us");

    FilterExpr expr = FilterParser.parse(raw);
    assertThat(expr).isInstanceOf(FilterExpr.And.class);
    List<FilterExpr> terms = ((FilterExpr.And) expr).terms();
    assertThat(terms.get(0)).isInstanceOf(FilterExpr.In.class);
    assertThat(terms.get(1)).isEqualTo(new FilterExpr.Equals("region", "us"));
  }

  @Test
  void orWithTwoEqualityClausesProducesOr() {
    Map<String, Object> raw =
        Map.of("$or", List.of(Map.of("category", "a"), Map.of("category", "b")));
    FilterExpr expr = FilterParser.parse(raw);
    assertThat(expr).isInstanceOf(FilterExpr.Or.class);
    List<FilterExpr> terms = ((FilterExpr.Or) expr).terms();
    assertThat(terms)
        .containsExactlyInAnyOrder(
            new FilterExpr.Equals("category", "a"), new FilterExpr.Equals("category", "b"));
  }

  @Test
  void orWithMultiKeyClauseParsesClauseAsImplicitAnd() {
    Map<String, Object> clause = new LinkedHashMap<>();
    clause.put("category", "shoes");
    clause.put("region", "us");
    Map<String, Object> raw = Map.of("$or", List.of(clause, Map.of("category", "books")));

    FilterExpr expr = FilterParser.parse(raw);
    assertThat(expr).isInstanceOf(FilterExpr.Or.class);
    List<FilterExpr> orTerms = ((FilterExpr.Or) expr).terms();
    assertThat(orTerms.get(0)).isInstanceOf(FilterExpr.And.class);
    assertThat(orTerms.get(1)).isEqualTo(new FilterExpr.Equals("category", "books"));
  }

  @Test
  void notWrapsInnerFilter() {
    Map<String, Object> raw = Map.of("$not", Map.of("category", "shoes"));
    FilterExpr expr = FilterParser.parse(raw);
    assertThat(expr)
        .isEqualTo(new FilterExpr.Not(new FilterExpr.Equals("category", "shoes")));
  }

  @Test
  void notMayMixWithSiblingEqualityKeys() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("$not", Map.of("category", "shoes"));
    raw.put("region", "us");

    FilterExpr expr = FilterParser.parse(raw);
    assertThat(expr).isInstanceOf(FilterExpr.And.class);
    List<FilterExpr> terms = ((FilterExpr.And) expr).terms();
    assertThat(terms.get(0)).isInstanceOf(FilterExpr.Not.class);
    assertThat(terms.get(1)).isEqualTo(new FilterExpr.Equals("region", "us"));
  }

  @Test
  void notOfOrComposes() {
    Map<String, Object> raw =
        Map.of("$not", Map.of("$or", List.of(Map.of("category", "a"), Map.of("category", "b"))));
    FilterExpr expr = FilterParser.parse(raw);
    assertThat(expr).isInstanceOf(FilterExpr.Not.class);
    FilterExpr inner = ((FilterExpr.Not) expr).term();
    assertThat(inner).isInstanceOf(FilterExpr.Or.class);
  }

  @Test
  void topLevelOrMixedWithSiblingRejectedAsAmbiguous() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("$or", List.of(Map.of("category", "a")));
    raw.put("region", "us");

    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(AmbiguousFilterException.class)
        .satisfies(
            e -> {
              AmbiguousFilterException afe = (AmbiguousFilterException) e;
              assertThat(afe.operator()).isEqualTo("$or");
              assertThat(afe.siblings()).isEqualTo(Set.of("region"));
            });
  }

  @Test
  void rangeOperatorRejectedAsUnsupported() {
    Map<String, Object> raw = Map.of("price", Map.of("$gt", 5));
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .satisfies(
            e -> {
              UnsupportedFilterOperatorException ufoe = (UnsupportedFilterOperatorException) e;
              assertThat(ufoe.key()).isEqualTo("price");
              assertThat(ufoe.operator()).isEqualTo("$gt");
            });
  }

  @Test
  void unknownTopLevelDollarOperatorRejected() {
    Map<String, Object> raw = Map.of("$nor", List.of(Map.of("category", "a")));
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .satisfies(
            e -> assertThat(((UnsupportedFilterOperatorException) e).operator()).isEqualTo("$nor"));
  }

  @Test
  void inWithNonStringValueRejected() {
    Map<String, Object> raw = Map.of("category", Map.of("$in", List.of("a", 1)));
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .hasMessageContaining("category")
        .hasMessageContaining("$in");
  }

  @Test
  void inWithEmptyArrayRejected() {
    Map<String, Object> raw = Map.of("category", Map.of("$in", List.of()));
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .hasMessageContaining("empty_array");
  }

  @Test
  void inMixedWithOtherLeafOperatorRejected() {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("$in", List.of("a"));
    envelope.put("$gt", 5);
    Map<String, Object> raw = Map.of("category", envelope);

    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class);
  }

  @Test
  void orWithEmptyArrayRejected() {
    Map<String, Object> raw = Map.of("$or", List.of());
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .hasMessageContaining("empty_array");
  }

  @Test
  void orWithNonMapClauseRejected() {
    Map<String, Object> raw = Map.of("$or", List.of("not-a-map"));
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class);
  }

  @Test
  void notWithNonMapValueRejected() {
    Map<String, Object> raw = Map.of("$not", "not-a-map");
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .hasMessageContaining("$not");
  }

  @Test
  void nonStringScalarValueRejected() {
    Map<String, Object> raw = Map.of("count", 42);
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .hasMessageContaining("count")
        .hasMessageContaining("Integer");
  }

  @Test
  void listValueRejected() {
    Map<String, Object> raw = Map.of("tags", List.of("a", "b"));
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .hasMessageContaining("tags");
  }

  @Test
  void nullValueRejected() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("category", null);
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .satisfies(
            e ->
                assertThat(((UnsupportedFilterOperatorException) e).operator()).isEqualTo("null"));
  }
}
