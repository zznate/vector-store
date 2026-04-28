package io.github.zznate.vectorstore.metadata.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.metadata.posting.PostingListReader;
import io.github.zznate.vectorstore.metadata.posting.PostingListWriter;
import io.github.zznate.vectorstore.metadata.sidecar.OrdinalAttributes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  // ---- scan strategy: filter == null ----

  @Test
  void nullFilterAcceptsEveryOrdinal() {
    OrdinalAttributes attrs = fixed(10, ordinal -> Map.of("category", "A"));

    RoaringBitsAdapter bits = compiler.compile(null, attrs, null, INDEX_ID, SEGMENT_ID);
    for (int i = 0; i < 10; i++) {
      assertThat(bits.get(i)).as("ordinal %d should accept", i).isTrue();
    }
    assertThat(bits.bitmap().getCardinality()).isEqualTo(10);
  }

  // ---- scan strategy: postings == null ----

  @Test
  void equalsMatchesOnlyOrdinalsWithMatchingKey() {
    OrdinalAttributes attrs = fixed(6, ordinal -> Map.of("category", ordinal % 2 == 0 ? "A" : "B"));

    RoaringBitsAdapter bits =
        compiler.compile(
            new FilterExpr.Equals("category", "A"), attrs, null, INDEX_ID, SEGMENT_ID);
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
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, null, INDEX_ID, SEGMENT_ID);
    assertThat(bits.bitmap().getCardinality()).isEqualTo(1);
    assertThat(bits.get(0)).isTrue();
    assertThat(bits.get(1)).isFalse();
    assertThat(bits.get(2)).isFalse();
  }

  @Test
  void missingAttributeDoesNotMatchEquals() {
    OrdinalAttributes attrs =
        fixed(3, ordinal -> ordinal == 0 ? Map.of("category", "A") : Map.of());
    RoaringBitsAdapter bits =
        compiler.compile(
            new FilterExpr.Equals("category", "A"), attrs, null, INDEX_ID, SEGMENT_ID);
    assertThat(bits.bitmap().getCardinality()).isEqualTo(1);
    assertThat(bits.get(0)).isTrue();
    assertThat(bits.get(1)).isFalse();
    assertThat(bits.get(2)).isFalse();
  }

  @Test
  void emptySegmentCompilesToEmptyBitmap() {
    RoaringBitsAdapter bits =
        compiler.compile(
            new FilterExpr.Equals("category", "A"),
            fixed(0, o -> Map.of()),
            null,
            INDEX_ID,
            SEGMENT_ID);
    assertThat(bits.bitmap().isEmpty()).isTrue();
    assertThat(bits.length()).isZero();
  }

  @Test
  void inMatchesAnyValueInTheSet() {
    OrdinalAttributes attrs =
        fixed(
            6,
            ordinal -> Map.of("category", ordinal % 3 == 0 ? "A" : ordinal % 3 == 1 ? "B" : "C"));

    RoaringBitsAdapter bits =
        compiler.compile(
            new FilterExpr.In("category", Set.of("A", "C")), attrs, null, INDEX_ID, SEGMENT_ID);
    assertThat(bits.bitmap().getCardinality()).isEqualTo(4);
    assertThat(bits.get(0)).isTrue();
    assertThat(bits.get(1)).isFalse();
    assertThat(bits.get(2)).isTrue();
    assertThat(bits.get(3)).isTrue();
    assertThat(bits.get(4)).isFalse();
    assertThat(bits.get(5)).isTrue();
  }

  @Test
  void inWithMissingAttributeDoesNotMatch() {
    OrdinalAttributes attrs =
        fixed(3, ordinal -> ordinal == 0 ? Map.of("category", "A") : Map.of());
    RoaringBitsAdapter bits =
        compiler.compile(
            new FilterExpr.In("category", Set.of("A", "B")), attrs, null, INDEX_ID, SEGMENT_ID);
    assertThat(bits.bitmap().getCardinality()).isEqualTo(1);
    assertThat(bits.get(0)).isTrue();
    assertThat(bits.get(1)).isFalse();
  }

  @Test
  void orUnionsMatches() {
    OrdinalAttributes attrs =
        fixed(5, ordinal -> Map.of("category", ordinal == 0 ? "A" : ordinal == 1 ? "B" : "C"));

    FilterExpr expr =
        new FilterExpr.Or(
            List.of(
                new FilterExpr.Equals("category", "A"),
                new FilterExpr.Equals("category", "B")));
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, null, INDEX_ID, SEGMENT_ID);
    assertThat(bits.bitmap().getCardinality()).isEqualTo(2);
    assertThat(bits.get(0)).isTrue();
    assertThat(bits.get(1)).isTrue();
    assertThat(bits.get(2)).isFalse();
  }

  @Test
  void notInvertsAgainstFullDomain() {
    OrdinalAttributes attrs = fixed(4, ordinal -> Map.of("category", ordinal % 2 == 0 ? "A" : "B"));

    RoaringBitsAdapter bits =
        compiler.compile(
            new FilterExpr.Not(new FilterExpr.Equals("category", "A")),
            attrs,
            null,
            INDEX_ID,
            SEGMENT_ID);
    assertThat(bits.bitmap().getCardinality()).isEqualTo(2);
    assertThat(bits.get(0)).isFalse();
    assertThat(bits.get(1)).isTrue();
    assertThat(bits.get(2)).isFalse();
    assertThat(bits.get(3)).isTrue();
  }

  @Test
  void notOfMissingAttributeAcceptsOrdinal() {
    OrdinalAttributes attrs =
        fixed(3, ordinal -> ordinal == 0 ? Map.of("category", "A") : Map.of());
    RoaringBitsAdapter bits =
        compiler.compile(
            new FilterExpr.Not(new FilterExpr.Equals("category", "A")),
            attrs,
            null,
            INDEX_ID,
            SEGMENT_ID);
    assertThat(bits.bitmap().getCardinality()).isEqualTo(2);
    assertThat(bits.get(0)).isFalse();
    assertThat(bits.get(1)).isTrue();
    assertThat(bits.get(2)).isTrue();
  }

  @Test
  void mixedOrInAndNotAgreesWithBruteForceReference() {
    int n = 200;
    OrdinalAttributes attrs = mixedFixture(n);

    FilterExpr expr =
        new FilterExpr.And(
            List.of(
                new FilterExpr.Or(
                    List.of(
                        new FilterExpr.In("category", Set.of("A", "B")),
                        new FilterExpr.Equals("region", "eu"))),
                new FilterExpr.Not(new FilterExpr.Equals("category", "C"))));
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, null, INDEX_ID, SEGMENT_ID);
    assertMatchesBruteForce(bits, attrs, expr);
  }

  @Test
  void compiledBitmapAgreesWithBruteForceReference() {
    int n = 200;
    OrdinalAttributes attrs = mixedFixture(n);

    FilterExpr expr =
        new FilterExpr.And(
            List.of(
                new FilterExpr.Equals("category", "A"),
                new FilterExpr.Equals("region", "us")));
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, null, INDEX_ID, SEGMENT_ID);
    assertMatchesBruteForce(bits, attrs, expr);
  }

  // ---- planner: strategy selection + counters ----

  @Test
  void scanStrategyChosenWhenPostingsNullAndCounterReflectsIt() {
    OrdinalAttributes attrs = fixed(3, ordinal -> Map.of("category", "A"));
    compiler.compile(
        new FilterExpr.Equals("category", "A"), attrs, null, INDEX_ID, SEGMENT_ID);
    assertThat(strategyCount("scan")).isEqualTo(1.0);
    assertThat(strategyCount("posting_list")).isZero();
  }

  @Test
  void scanStrategyChosenWhenAnyLeafKeyIsNotIndexed(@TempDir Path tmp) throws IOException {
    PostingListReader postings = writeAndRead(tmp, postingsCorpus());
    OrdinalAttributes attrs = mixedFixture(6);

    FilterExpr expr =
        new FilterExpr.And(
            List.of(
                new FilterExpr.Equals("category", "A"),
                new FilterExpr.Equals("not_indexed", "x")));
    compiler.compile(expr, attrs, postings, INDEX_ID, SEGMENT_ID);

    assertThat(strategyCount("scan")).isEqualTo(1.0);
    assertThat(strategyCount("posting_list")).isZero();
  }

  @Test
  void postingListStrategyChosenWhenAllLeavesResolve(@TempDir Path tmp) throws IOException {
    PostingListReader postings = writeAndRead(tmp, postingsCorpus());
    OrdinalAttributes attrs = mixedFixture(6);

    FilterExpr expr =
        new FilterExpr.And(
            List.of(
                new FilterExpr.Equals("category", "A"),
                new FilterExpr.Equals("region", "us")));
    compiler.compile(expr, attrs, postings, INDEX_ID, SEGMENT_ID);

    assertThat(strategyCount("posting_list")).isEqualTo(1.0);
    assertThat(strategyCount("scan")).isZero();
  }

  // ---- planner: posting-list strategy correctness ----

  @Test
  void postingListEqualsMatchesBruteForce(@TempDir Path tmp) throws IOException {
    int n = 30;
    OrdinalAttributes attrs = mixedFixture(n);
    PostingListReader postings = writeAndRead(tmp, materialise(attrs));

    FilterExpr expr = new FilterExpr.Equals("category", "A");
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, postings, INDEX_ID, SEGMENT_ID);
    assertMatchesBruteForce(bits, attrs, expr);
  }

  @Test
  void postingListInMatchesBruteForce(@TempDir Path tmp) throws IOException {
    int n = 30;
    OrdinalAttributes attrs = mixedFixture(n);
    PostingListReader postings = writeAndRead(tmp, materialise(attrs));

    FilterExpr expr = new FilterExpr.In("category", Set.of("A", "C"));
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, postings, INDEX_ID, SEGMENT_ID);
    assertMatchesBruteForce(bits, attrs, expr);
  }

  @Test
  void postingListNotMatchesBruteForce(@TempDir Path tmp) throws IOException {
    int n = 30;
    OrdinalAttributes attrs = mixedFixture(n);
    PostingListReader postings = writeAndRead(tmp, materialise(attrs));

    FilterExpr expr = new FilterExpr.Not(new FilterExpr.Equals("category", "A"));
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, postings, INDEX_ID, SEGMENT_ID);
    assertMatchesBruteForce(bits, attrs, expr);
  }

  @Test
  void postingListMixedExpressionMatchesBruteForce(@TempDir Path tmp) throws IOException {
    int n = 200;
    OrdinalAttributes attrs = mixedFixture(n);
    PostingListReader postings = writeAndRead(tmp, materialise(attrs));

    FilterExpr expr =
        new FilterExpr.And(
            List.of(
                new FilterExpr.Or(
                    List.of(
                        new FilterExpr.In("category", Set.of("A", "B")),
                        new FilterExpr.Equals("region", "eu"))),
                new FilterExpr.Not(new FilterExpr.Equals("category", "C"))));
    RoaringBitsAdapter bits = compiler.compile(expr, attrs, postings, INDEX_ID, SEGMENT_ID);
    assertMatchesBruteForce(bits, attrs, expr);
  }

  @Test
  void postingListAbsentValueResolvesToEmptyBitmap(@TempDir Path tmp) throws IOException {
    OrdinalAttributes attrs = fixed(4, ordinal -> Map.of("category", "A"));
    PostingListReader postings = writeAndRead(tmp, materialise(attrs));

    RoaringBitsAdapter bits =
        compiler.compile(
            new FilterExpr.Equals("category", "ZZZ"), attrs, postings, INDEX_ID, SEGMENT_ID);
    assertThat(bits.bitmap().isEmpty()).isTrue();
    assertThat(strategyCount("posting_list")).isEqualTo(1.0);
  }

  // ---- duration histogram tagging ----

  @Test
  void durationTimerCarriesStrategyTag() {
    OrdinalAttributes attrs = fixed(10, ordinal -> Map.of("category", ordinal < 3 ? "A" : "B"));
    compiler.compile(
        new FilterExpr.Equals("category", "A"), attrs, null, INDEX_ID, SEGMENT_ID);

    double count =
        registry
            .timer(
                "vectorstore.filter.compile.duration",
                "index_id", INDEX_ID,
                "term_count", "1",
                "result_ratio_bucket", "25-50",
                "strategy", "scan")
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

  // ---- helpers ----

  private double strategyCount(String strategy) {
    return registry.counter("vectorstore.filter.strategy", "strategy", strategy, "index_id", INDEX_ID).count();
  }

  private static OrdinalAttributes mixedFixture(int n) {
    return fixed(
        n,
        ordinal ->
            Map.of(
                "category", ordinal % 3 == 0 ? "A" : ordinal % 3 == 1 ? "B" : "C",
                "region", ordinal % 2 == 0 ? "us" : "eu"));
  }

  private static List<Map<String, String>> materialise(OrdinalAttributes attrs) {
    List<Map<String, String>> list = new ArrayList<>(attrs.size());
    for (int i = 0; i < attrs.size(); i++) {
      list.add(attrs.attributesOf(i));
    }
    return list;
  }

  private static List<Map<String, String>> postingsCorpus() {
    return List.of(
        Map.of("category", "A", "region", "us"),
        Map.of("category", "B", "region", "eu"),
        Map.of("category", "A", "region", "eu"),
        Map.of("category", "C", "region", "us"));
  }

  private static PostingListReader writeAndRead(Path dir, List<Map<String, String>> byOrdinal)
      throws IOException {
    Path path = dir.resolve("postings.bin");
    PostingListWriter.write(path, byOrdinal, 1000);
    try (InputStream in = Files.newInputStream(path)) {
      return PostingListReader.read(in);
    }
  }

  private static void assertMatchesBruteForce(
      RoaringBitsAdapter bits, OrdinalAttributes attrs, FilterExpr expr) {
    for (int i = 0; i < attrs.size(); i++) {
      boolean expected = bruteForce(expr, attrs.attributesOf(i));
      assertThat(bits.get(i)).as("ordinal %d", i).isEqualTo(expected);
    }
  }

  private static boolean bruteForce(FilterExpr expr, Map<String, String> row) {
    return switch (expr) {
      case FilterExpr.Equals eq -> eq.value().equals(row.get(eq.key()));
      case FilterExpr.In in -> {
        String value = row.get(in.key());
        yield value != null && in.values().contains(value);
      }
      case FilterExpr.And and -> {
        for (FilterExpr t : and.terms()) {
          if (!bruteForce(t, row)) {
            yield false;
          }
        }
        yield true;
      }
      case FilterExpr.Or or -> {
        for (FilterExpr t : or.terms()) {
          if (bruteForce(t, row)) {
            yield true;
          }
        }
        yield false;
      }
      case FilterExpr.Not not -> !bruteForce(not.term(), row);
    };
  }

  private static OrdinalAttributes fixed(
      int size, java.util.function.IntFunction<Map<String, String>> supplier) {
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
