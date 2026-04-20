package io.github.zznate.vectorstore.metadata.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.metadata.sidecar.OrdinalAttributes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FilterCompilerTest {

  private static final String INDEX_ID = "demo/products";
  private static final String SEGMENT_ID = "01926a5b-0000-7f00-9000-000000000001";

  private MeterRegistry registry;
  private Tracer tracer;
  private FilterCompiler compiler;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    tracer = TracerProvider.noop().get("test");
    compiler = new FilterCompiler(registry, tracer);
  }

  @Test
  void nullFilterAcceptsEveryOrdinal() {
    OrdinalAttributes attrs = fixed(10, ordinal -> Map.of("category", "A"));

    RoaringBitsAdapter bits = compiler.compile(null, attrs, INDEX_ID, SEGMENT_ID);
    for (int i = 0; i < 10; i++) {
      assertThat(bits.get(i)).as("ordinal %d should accept", i).isTrue();
    }
    assertThat(bits.bitmap().getCardinality()).isEqualTo(10);
  }

  @Test
  void equalsMatchesOnlyOrdinalsWithMatchingKey() {
    OrdinalAttributes attrs =
        fixed(
            6,
            ordinal -> Map.of("category", ordinal % 2 == 0 ? "A" : "B"));

    RoaringBitsAdapter bits =
        compiler.compile(new FilterExpr.Equals("category", "A"), attrs, INDEX_ID, SEGMENT_ID);
    List<Integer> matching = new ArrayList<>();
    for (int i = 0; i < attrs.size(); i++) {
      if (bits.get(i)) {
        matching.add(i);
      }
    }
    assertThat(matching).containsExactly(0, 2, 4);
  }

  @Test
  void andOfTwoEqualsIntersectsMatches() {
    OrdinalAttributes attrs =
        fixed(
            4,
            ordinal -> {
              Map<String, String> m = new HashMap<>();
              m.put("category", ordinal < 2 ? "shoes" : "books");
              m.put("region", ordinal % 2 == 0 ? "us" : "eu");
              return m;
            });

    FilterExpr expr =
        new FilterExpr.And(
            List.of(
                new FilterExpr.Equals("category", "shoes"),
                new FilterExpr.Equals("region", "us")));
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, INDEX_ID, SEGMENT_ID);
    assertThat(bits.bitmap().getCardinality()).isEqualTo(1);
    assertThat(bits.get(0)).isTrue(); // shoes + us
    assertThat(bits.get(1)).isFalse(); // shoes + eu
    assertThat(bits.get(2)).isFalse(); // books + us
  }

  @Test
  void missingAttributeDoesNotMatchEquals() {
    OrdinalAttributes attrs =
        fixed(3, ordinal -> ordinal == 0 ? Map.of("category", "A") : Map.of());
    RoaringBitsAdapter bits =
        compiler.compile(new FilterExpr.Equals("category", "A"), attrs, INDEX_ID, SEGMENT_ID);
    assertThat(bits.bitmap().getCardinality()).isEqualTo(1);
    assertThat(bits.get(0)).isTrue();
    assertThat(bits.get(1)).isFalse();
    assertThat(bits.get(2)).isFalse();
  }

  @Test
  void emptySegmentCompilesToEmptyBitmap() {
    RoaringBitsAdapter bits =
        compiler.compile(
            new FilterExpr.Equals("category", "A"), fixed(0, o -> Map.of()), INDEX_ID, SEGMENT_ID);
    assertThat(bits.bitmap().isEmpty()).isTrue();
    assertThat(bits.length()).isZero();
  }

  @Test
  void durationTimerTaggedByIndexTermCountAndRatioBucket() {
    OrdinalAttributes attrs =
        fixed(10, ordinal -> Map.of("category", ordinal < 3 ? "A" : "B"));

    compiler.compile(new FilterExpr.Equals("category", "A"), attrs, INDEX_ID, SEGMENT_ID);

    // 3/10 matches -> "25-50" bucket.
    double count =
        registry
            .timer(
                "vectorstore.filter.compile.duration",
                "index_id", INDEX_ID,
                "term_count", "1",
                "result_ratio_bucket", "25-50")
            .count();
    assertThat(count).isEqualTo(1.0);
  }

  @Test
  void resultRatioBucketBoundariesMatchSpec() {
    assertThat(FilterCompiler.bucketFor(100, 0)).isEqualTo("0-25");
    assertThat(FilterCompiler.bucketFor(100, 24)).isEqualTo("0-25");
    assertThat(FilterCompiler.bucketFor(100, 25)).isEqualTo("25-50");
    assertThat(FilterCompiler.bucketFor(100, 49)).isEqualTo("25-50");
    assertThat(FilterCompiler.bucketFor(100, 50)).isEqualTo("50-75");
    assertThat(FilterCompiler.bucketFor(100, 74)).isEqualTo("50-75");
    assertThat(FilterCompiler.bucketFor(100, 75)).isEqualTo("75-100");
    assertThat(FilterCompiler.bucketFor(100, 100)).isEqualTo("75-100");
    assertThat(FilterCompiler.bucketFor(0, 0)).isEqualTo("0-25");
  }

  @Test
  void compiledBitmapAgreesWithBruteForceReference() {
    int n = 200;
    OrdinalAttributes attrs =
        fixed(
            n,
            ordinal ->
                Map.of(
                    "category", ordinal % 3 == 0 ? "A" : ordinal % 3 == 1 ? "B" : "C",
                    "region", ordinal % 2 == 0 ? "us" : "eu"));

    FilterExpr expr =
        new FilterExpr.And(
            List.of(
                new FilterExpr.Equals("category", "A"),
                new FilterExpr.Equals("region", "us")));
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, INDEX_ID, SEGMENT_ID);

    for (int i = 0; i < n; i++) {
      Map<String, String> row = attrs.attributesOf(i);
      boolean expected = "A".equals(row.get("category")) && "us".equals(row.get("region"));
      assertThat(bits.get(i))
          .as("bit %d should match brute-force reference", i)
          .isEqualTo(expected);
    }
  }

  private static OrdinalAttributes fixed(int size, java.util.function.IntFunction<Map<String, String>> supplier) {
    return new OrdinalAttributes() {
      @Override
      public int size() {
        return size;
      }

      @Override
      public Map<String, String> attributesOf(int ordinal) {
        if (ordinal < 0 || ordinal >= size) {
          throw new IndexOutOfBoundsException(ordinal);
        }
        return supplier.apply(ordinal);
      }
    };
  }
}
