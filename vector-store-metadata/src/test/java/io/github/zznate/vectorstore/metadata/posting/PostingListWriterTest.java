package io.github.zznate.vectorstore.metadata.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

class PostingListWriterTest {

  @Test
  void roundTripsAllOrdinalsAndKeys(@TempDir Path tmp) throws IOException {
    List<Map<String, String>> byOrdinal =
        List.of(
            attrs("category", "shoes", "region", "us"),
            attrs("category", "books", "region", "eu"),
            attrs("category", "shoes", "region", "us"),
            attrs("category", "shoes", "region", "eu"),
            attrs("category", "books", "region", "us"));

    Path path = tmp.resolve("postings.bin");
    PostingListWriter.WriteResult result = PostingListWriter.write(path, byOrdinal, 1000);
    assertThat(result.skippedKeys()).isEmpty();
    assertThat(result.bytesWritten()).isPositive();

    PostingListReader reader = readFile(path);
    assertThat(reader.indexedKeys()).containsExactlyInAnyOrder("category", "region");

    assertThat(asList(reader, "category", "shoes")).containsExactly(0, 2, 3);
    assertThat(asList(reader, "category", "books")).containsExactly(1, 4);
    assertThat(asList(reader, "region", "us")).containsExactly(0, 2, 4);
    assertThat(asList(reader, "region", "eu")).containsExactly(1, 3);
  }

  @Test
  void emptyOrdinalListProducesValidEmptyFile(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("postings.bin");
    PostingListWriter.WriteResult result = PostingListWriter.write(path, List.of(), 1000);
    assertThat(result.bytesWritten()).isEqualTo(PostingListFormat.HEADER_BYTES);
    assertThat(result.skippedKeys()).isEmpty();

    PostingListReader reader = readFile(path);
    assertThat(reader.termCount()).isZero();
    assertThat(reader.indexedKeys()).isEmpty();
    assertThat(reader.lookup("category", "shoes")).isEmpty();
  }

  @Test
  void absentTermLookupReturnsEmpty(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("postings.bin");
    PostingListWriter.write(
        path,
        List.of(attrs("category", "shoes")),
        1000);
    PostingListReader reader = readFile(path);
    assertThat(reader.lookup("category", "books")).isEmpty();
    assertThat(reader.lookup("region", "us")).isEmpty();
  }

  @Test
  void ordinalsWithoutAttributesAreOmittedFromBitmaps(@TempDir Path tmp) throws IOException {
    List<Map<String, String>> byOrdinal = new java.util.ArrayList<>();
    byOrdinal.add(attrs("category", "shoes"));
    byOrdinal.add(Map.of()); // ordinal 1 has no attributes
    byOrdinal.add(attrs("category", "shoes"));
    byOrdinal.add(null); // ordinal 3 has null attributes

    Path path = tmp.resolve("postings.bin");
    PostingListWriter.write(path, byOrdinal, 1000);
    PostingListReader reader = readFile(path);
    assertThat(asList(reader, "category", "shoes")).containsExactly(0, 2);
  }

  @Test
  void highCardinalityKeyIsSkippedAndReportedInResult(@TempDir Path tmp) throws IOException {
    List<Map<String, String>> byOrdinal = new java.util.ArrayList<>();
    for (int i = 0; i < 12; i++) {
      // every value distinct -> 12 distinct values
      byOrdinal.add(attrs("id", "u-" + i, "category", i % 2 == 0 ? "A" : "B"));
    }

    Path path = tmp.resolve("postings.bin");
    PostingListWriter.WriteResult result = PostingListWriter.write(path, byOrdinal, 10);
    assertThat(result.skippedKeys()).containsExactly("id");

    PostingListReader reader = readFile(path);
    assertThat(reader.indexedKeys()).containsExactly("category");
    assertThat(asList(reader, "category", "A")).hasSize(6);
    assertThat(reader.lookup("id", "u-0")).isEmpty();
  }

  @Test
  void cardinalityCapAtThresholdIsInclusive(@TempDir Path tmp) throws IOException {
    List<Map<String, String>> byOrdinal = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      byOrdinal.add(attrs("k", "v-" + i));
    }
    Path path = tmp.resolve("postings.bin");
    PostingListWriter.WriteResult result = PostingListWriter.write(path, byOrdinal, 5);
    assertThat(result.skippedKeys()).isEmpty();
    PostingListReader reader = readFile(path);
    assertThat(reader.indexedKeys()).containsExactly("k");
  }

  @Test
  void headerCarriesMagicVersionAndOffsets(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("postings.bin");
    PostingListWriter.write(
        path,
        List.of(attrs("category", "shoes"), attrs("category", "books")),
        1000);

    byte[] bytes = Files.readAllBytes(path);
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    assertThat(buf.getInt()).isEqualTo(PostingListFormat.MAGIC);
    assertThat(buf.getInt()).isEqualTo(PostingListFormat.VERSION);
    assertThat(buf.getInt()).isEqualTo(2); // term_count
    long indexOff = buf.getLong();
    long dataOff = buf.getLong();
    assertThat(indexOff).isEqualTo(PostingListFormat.HEADER_BYTES);
    assertThat(dataOff).isGreaterThan(indexOff);
  }

  @Test
  void corruptMagicRejected() {
    byte[] bytes = new byte[PostingListFormat.HEADER_BYTES];
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    buf.putInt(0xDEADBEEF);
    buf.putInt(PostingListFormat.VERSION);
    assertThatThrownBy(() -> PostingListReader.parse(bytes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("magic");
  }

  @Test
  void unknownVersionRejected() {
    byte[] bytes = new byte[PostingListFormat.HEADER_BYTES];
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    buf.putInt(PostingListFormat.MAGIC);
    buf.putInt(PostingListFormat.VERSION + 99);
    assertThatThrownBy(() -> PostingListReader.parse(bytes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
  }

  @Test
  void utf8KeysAndValuesRoundTrip(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("postings.bin");
    List<Map<String, String>> byOrdinal =
        List.of(attrs("régión", "us-éast"), attrs("régión", "ÅSIA"));
    PostingListWriter.write(path, byOrdinal, 1000);
    PostingListReader reader = readFile(path);
    assertThat(asList(reader, "régión", "us-éast")).containsExactly(0);
    assertThat(asList(reader, "régión", "ÅSIA")).containsExactly(1);
  }

  // ---- helpers ----

  private static PostingListReader readFile(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return PostingListReader.read(in);
    }
  }

  private static List<Integer> asList(PostingListReader reader, String key, String value) {
    RoaringBitmap bm =
        reader.lookup(key, value).orElseThrow(() -> new AssertionError("missing " + key + "=" + value));
    java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
    bm.forEach((org.roaringbitmap.IntConsumer) out::add);
    return out;
  }

  private static Map<String, String> attrs(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

}
