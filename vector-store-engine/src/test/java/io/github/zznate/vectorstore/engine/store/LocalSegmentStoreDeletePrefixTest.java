package io.github.zznate.vectorstore.engine.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSegmentStoreDeletePrefixTest {

  @TempDir Path root;
  private LocalSegmentStore store;

  @BeforeEach
  void setUp() {
    store = new LocalSegmentStore(root);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void deletePrefixRemovesEntireSubtree() throws Exception {
    Path segmentDir = root.resolve("demo/products/seg-1");
    Files.createDirectories(segmentDir);
    Files.writeString(segmentDir.resolve("graph.jvec"), "graph");
    Files.writeString(segmentDir.resolve("attributes.jsonl"), "attrs");
    Files.writeString(segmentDir.resolve("postings.bin"), "post");

    store.deletePrefix("demo/products/seg-1");

    assertThat(Files.exists(segmentDir)).isFalse();
  }

  @Test
  void deletePrefixOnAbsentPrefixIsNoop() throws Exception {
    // No exception, no error, returns cleanly. Idempotency contract.
    store.deletePrefix("nonexistent/path");
  }

  @Test
  void deletePrefixLeavesUnrelatedSiblings() throws Exception {
    Path target = root.resolve("demo/products/seg-1");
    Path sibling = root.resolve("demo/products/seg-2");
    Files.createDirectories(target);
    Files.createDirectories(sibling);
    Files.writeString(target.resolve("graph.jvec"), "g1");
    Files.writeString(sibling.resolve("graph.jvec"), "g2");

    store.deletePrefix("demo/products/seg-1");

    assertThat(Files.exists(target)).isFalse();
    assertThat(Files.exists(sibling)).isTrue();
    assertThat(Files.exists(sibling.resolve("graph.jvec"))).isTrue();
  }
}
