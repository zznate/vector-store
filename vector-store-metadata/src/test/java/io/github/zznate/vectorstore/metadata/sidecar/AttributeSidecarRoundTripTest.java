package io.github.zznate.vectorstore.metadata.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AttributeSidecarRoundTripTest {

  @Test
  void emptyListProducesEmptySidecar() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AttributeSidecarWriter.write(out, List.of());
    AttributeSidecar sidecar = AttributeSidecar.parse(new ByteArrayInputStream(out.toByteArray()));
    assertThat(sidecar.size()).isZero();
  }

  @Test
  void ordinalsWithAndWithoutAttributesRoundTrip() throws Exception {
    List<Map<String, String>> byOrdinal =
        List.of(
            Map.of("category", "shoes", "region", "us"),
            Map.of(),
            Map.of("category", "books"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AttributeSidecarWriter.write(out, byOrdinal);
    AttributeSidecar parsed = AttributeSidecar.parse(new ByteArrayInputStream(out.toByteArray()));

    assertThat(parsed.size()).isEqualTo(3);
    assertThat(parsed.attributesOf(0)).isEqualTo(byOrdinal.get(0));
    assertThat(parsed.attributesOf(1)).isEmpty();
    assertThat(parsed.attributesOf(2)).isEqualTo(byOrdinal.get(2));
  }

  @Test
  void randomisedRoundTripIsExact() throws Exception {
    Random rng = new Random(2026_04_20L);
    int n = 500;
    String[] categories = {"shoes", "books", "electronics", "groceries", "garden"};
    String[] regions = {"us", "eu", "ap"};

    List<Map<String, String>> byOrdinal = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Map<String, String> attrs = new HashMap<>();
      attrs.put("category", categories[rng.nextInt(categories.length)]);
      if (rng.nextBoolean()) {
        attrs.put("region", regions[rng.nextInt(regions.length)]);
      }
      byOrdinal.add(attrs);
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AttributeSidecarWriter.write(out, byOrdinal);
    AttributeSidecar parsed = AttributeSidecar.parse(new ByteArrayInputStream(out.toByteArray()));

    assertThat(parsed.size()).isEqualTo(n);
    for (int i = 0; i < n; i++) {
      assertThat(parsed.attributesOf(i))
          .as("ordinal %d", i)
          .containsExactlyInAnyOrderEntriesOf(byOrdinal.get(i));
    }
  }

  @Test
  void sizeBytesReflectsActualJsonlPayload() throws Exception {
    List<Map<String, String>> byOrdinal =
        List.of(Map.of("category", "shoes"), Map.of("category", "books"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AttributeSidecarWriter.write(out, byOrdinal);

    AttributeSidecar parsed = AttributeSidecar.parse(new ByteArrayInputStream(out.toByteArray()));
    assertThat(parsed.sizeBytes()).isPositive();
    // Sanity: approx heap footprint should be within an order of magnitude
    // of the raw JSONL size.
    assertThat(parsed.sizeBytes()).isLessThan(out.size() * 10L);
  }

  @Test
  void ofInMemoryListMatchesParsedSidecarStructurally() {
    List<Map<String, String>> byOrdinal =
        List.of(Map.of("a", "1"), Map.of("b", "2"), Map.of());
    AttributeSidecar sidecar = AttributeSidecar.of(byOrdinal);
    assertThat(sidecar.size()).isEqualTo(3);
    assertThat(sidecar.attributesOf(0)).isEqualTo(Map.of("a", "1"));
    assertThat(sidecar.attributesOf(1)).isEqualTo(Map.of("b", "2"));
    assertThat(sidecar.attributesOf(2)).isEmpty();
    assertThat(sidecar.sizeBytes()).isPositive();
  }
}
