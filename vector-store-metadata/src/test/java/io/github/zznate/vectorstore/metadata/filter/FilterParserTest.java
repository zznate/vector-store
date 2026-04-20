package io.github.zznate.vectorstore.metadata.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    // LinkedHashMap preserves insertion order so the assertion below is stable.
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
  void nestedMapRejectsWithOperatorName() {
    Map<String, Object> raw = Map.of("category", Map.of("$in", List.of("A", "B")));
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .satisfies(
            e -> {
              UnsupportedFilterOperatorException ufoe = (UnsupportedFilterOperatorException) e;
              assertThat(ufoe.key()).isEqualTo("category");
              assertThat(ufoe.operator()).isEqualTo("$in");
            });
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
    // HashMap allows null values; Map.of does not.
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("category", null);
    assertThatThrownBy(() -> FilterParser.parse(raw))
        .isInstanceOf(UnsupportedFilterOperatorException.class)
        .satisfies(
            e ->
                assertThat(((UnsupportedFilterOperatorException) e).operator()).isEqualTo("null"));
  }
}
