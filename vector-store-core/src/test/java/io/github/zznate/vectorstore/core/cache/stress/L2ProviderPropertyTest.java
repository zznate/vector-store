package io.github.zznate.vectorstore.core.cache.stress;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.cache.L2Provider;
import io.github.zznate.vectorstore.core.cache.LmdbL2Provider;
import io.github.zznate.vectorstore.core.cache.SlabOffHeapL2Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property-based tests over random op sequences applied single-threaded
 * against {@link L2Provider} implementations. Properties cover four
 * invariants: oracle equality at end of sequence, {@code invalidateAll}
 * empties everything, byte accounting matches the surviving payload sum,
 * and (LMDB-only) closing and reopening preserves state.
 *
 * <p>Sequences are bounded by {@link #MAX_LENGTH} (override at runtime
 * with {@code -Dl2.property.maxLength=N}) and operate on a small key
 * pool with payloads up to {@value #PAYLOAD_MAX} bytes — well within
 * the {@value #MAX_BYTES_LITERAL_BYTES}-byte cap so eviction stays out
 * of scope. Tries are controlled by {@code jqwik.tries.default} in
 * {@code src/test/resources/junit-platform.properties}.
 */
class L2ProviderPropertyTest {

  private static final int MAX_LENGTH = Integer.getInteger("l2.property.maxLength", 50);
  private static final int KEY_POOL = 16;
  private static final int PAYLOAD_MIN = 1;
  private static final int PAYLOAD_MAX = 1024;
  private static final long MAX_BYTES = 16L << 20;
  private static final int MAX_BYTES_LITERAL_BYTES = 16 * 1024 * 1024;
  private static final int BLOCK_SIZE = 64 * 1024;

  @Property
  void slabFinalStateMatchesOracle(@ForAll("opSequence") List<OpSpec> ops) {
    try (L2Provider provider = newSlab()) {
      Map<String, byte[]> oracle = new HashMap<>();
      apply(provider, oracle, ops);
      assertOracleEquality(provider, oracle);
    }
  }

  @Property
  void lmdbFinalStateMatchesOracle(@ForAll("opSequence") List<OpSpec> ops) {
    Path dir = mkTempDir("prop-lmdb-");
    try (L2Provider provider = newLmdb(dir)) {
      Map<String, byte[]> oracle = new HashMap<>();
      apply(provider, oracle, ops);
      assertOracleEquality(provider, oracle);
    } finally {
      deleteTree(dir);
    }
  }

  @Property
  void slabInvalidateAllEmpties(@ForAll("opSequence") List<OpSpec> ops) {
    try (L2Provider provider = newSlab()) {
      apply(provider, new HashMap<>(), ops);
      provider.invalidateAll();
      for (int i = 0; i < KEY_POOL; i++) {
        assertThat(provider.get("k-" + i)).isEmpty();
      }
      assertThat(provider.stats().currentBytes()).isZero();
    }
  }

  @Property
  void slabByteAccountingMatchesOracle(@ForAll("opSequence") List<OpSpec> ops) {
    try (L2Provider provider = newSlab()) {
      Map<String, byte[]> oracle = new HashMap<>();
      apply(provider, oracle, ops);
      long expected = oracle.values().stream().mapToLong(b -> b.length).sum();
      assertThat(provider.stats().currentBytes()).isEqualTo(expected);
    }
  }

  @Property
  void lmdbRestartPreservesState(@ForAll("opSequence") List<OpSpec> ops) {
    Path dir = mkTempDir("prop-lmdb-restart-");
    Map<String, byte[]> oracle = new HashMap<>();
    try {
      try (L2Provider provider = newLmdb(dir)) {
        apply(provider, oracle, ops);
      }
      try (L2Provider reopened = newLmdb(dir)) {
        for (Map.Entry<String, byte[]> e : oracle.entrySet()) {
          Optional<byte[]> hit = reopened.get(e.getKey());
          assertThat(hit)
              .withFailMessage("LMDB restart: key %s missing after reopen", e.getKey())
              .isPresent();
          assertThat(hit.get()).isEqualTo(e.getValue());
        }
      }
    } finally {
      deleteTree(dir);
    }
  }

  @Provide
  Arbitrary<List<OpSpec>> opSequence() {
    return opSpec().list().ofMaxSize(MAX_LENGTH);
  }

  @Provide
  Arbitrary<OpSpec> opSpec() {
    Arbitrary<OpKind> kind =
        Arbitraries.frequencyOf(
            Tuple.of(50, Arbitraries.just(OpKind.PUT)),
            Tuple.of(25, Arbitraries.just(OpKind.GET)),
            Tuple.of(20, Arbitraries.just(OpKind.INVALIDATE)),
            Tuple.of(5, Arbitraries.just(OpKind.INVALIDATE_ALL)));
    Arbitrary<Integer> keyIdx = Arbitraries.integers().between(0, KEY_POOL - 1);
    Arbitrary<byte[]> payload =
        Arbitraries.bytes().array(byte[].class).ofMinSize(PAYLOAD_MIN).ofMaxSize(PAYLOAD_MAX);
    return Combinators.combine(kind, keyIdx, payload).as(OpSpec::new);
  }

  private static void apply(L2Provider provider, Map<String, byte[]> oracle, List<OpSpec> ops) {
    for (OpSpec op : ops) {
      String key = "k-" + op.keyIdx();
      switch (op.kind()) {
        case PUT -> {
          provider.put(key, op.payload());
          oracle.put(key, op.payload());
        }
        case GET -> provider.get(key);
        case INVALIDATE -> {
          provider.invalidate(key);
          oracle.remove(key);
        }
        case INVALIDATE_ALL -> {
          provider.invalidateAll();
          oracle.clear();
        }
      }
    }
  }

  private static void assertOracleEquality(L2Provider provider, Map<String, byte[]> oracle) {
    for (Map.Entry<String, byte[]> e : oracle.entrySet()) {
      Optional<byte[]> hit = provider.get(e.getKey());
      assertThat(hit)
          .withFailMessage("oracle key %s missing from provider", e.getKey())
          .isPresent();
      assertThat(hit.get()).isEqualTo(e.getValue());
    }
  }

  private static SlabOffHeapL2Provider newSlab() {
    return new SlabOffHeapL2Provider(MAX_BYTES, BLOCK_SIZE, new SimpleMeterRegistry(), "prop");
  }

  private static LmdbL2Provider newLmdb(Path dir) {
    return new LmdbL2Provider(dir, MAX_BYTES, new SimpleMeterRegistry(), "prop");
  }

  private static Path mkTempDir(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteTree(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  enum OpKind {
    PUT,
    GET,
    INVALIDATE,
    INVALIDATE_ALL
  }

  record OpSpec(OpKind kind, int keyIdx, byte[] payload) {}
}
