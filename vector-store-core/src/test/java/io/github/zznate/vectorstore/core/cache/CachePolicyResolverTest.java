package io.github.zznate.vectorstore.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.zznate.vectorstore.core.catalog.model.DistanceMetric;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CachePolicyResolverTest {

  @Test
  void smartIsDefaultForLegacyEngineParams() {
    StubRepo repo = new StubRepo();
    repo.register(indexWithRawJson("demo/widgets", null));
    CachePolicyResolver resolver = new CachePolicyResolver(repo);

    assertThat(resolver.policyFor("demo/widgets")).isEqualTo(CachePolicy.SMART);
    assertThat(resolver.cacheBytesFor("demo/widgets")).isEmpty();
  }

  @Test
  void residentIsResolvedFromEngineParams() {
    StubRepo repo = new StubRepo();
    repo.register(
        indexWithParams(
            "demo/hot",
            new IndexBuildParams(
                32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.RESIDENT, null)));
    CachePolicyResolver resolver = new CachePolicyResolver(repo);

    assertThat(resolver.policyFor("demo/hot")).isEqualTo(CachePolicy.RESIDENT);
  }

  @Test
  void cacheBytesPropagateThroughResolver() {
    StubRepo repo = new StubRepo();
    repo.register(
        indexWithParams(
            "demo/cap",
            new IndexBuildParams(
                32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.MINIMAL, 12_345L)));
    CachePolicyResolver resolver = new CachePolicyResolver(repo);

    assertThat(resolver.cacheBytesFor("demo/cap")).contains(12_345L);
  }

  @Test
  void resolverCachesAfterFirstLookup() {
    StubRepo repo = new StubRepo();
    repo.register(indexWithParams("demo/cached", IndexBuildParams.defaults()));
    CachePolicyResolver resolver = new CachePolicyResolver(repo);

    resolver.policyFor("demo/cached");
    resolver.policyFor("demo/cached");
    resolver.policyFor("demo/cached");

    assertThat(repo.findByIdInvocations.get()).isEqualTo(1);
  }

  @Test
  void invalidateForcesReloadOnNextLookup() {
    StubRepo repo = new StubRepo();
    repo.register(indexWithParams("demo/inv", IndexBuildParams.defaults()));
    CachePolicyResolver resolver = new CachePolicyResolver(repo);

    resolver.policyFor("demo/inv");
    resolver.invalidate("demo/inv");
    resolver.policyFor("demo/inv");

    assertThat(repo.findByIdInvocations.get()).isEqualTo(2);
  }

  @Test
  void invalidateAllClearsEveryEntry() {
    StubRepo repo = new StubRepo();
    repo.register(indexWithParams("demo/a", IndexBuildParams.defaults()));
    repo.register(indexWithParams("demo/b", IndexBuildParams.defaults()));
    CachePolicyResolver resolver = new CachePolicyResolver(repo);

    resolver.policyFor("demo/a");
    resolver.policyFor("demo/b");
    resolver.invalidateAll();
    resolver.policyFor("demo/a");
    resolver.policyFor("demo/b");

    assertThat(repo.findByIdInvocations.get()).isEqualTo(4);
  }

  @Test
  void unknownIndexThrows() {
    CachePolicyResolver resolver = new CachePolicyResolver(new StubRepo());
    assertThatThrownBy(() -> resolver.policyFor("missing"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing");
  }

  private static VectorIndex indexWithRawJson(String indexId, String engineParams) {
    String[] parts = indexId.split("/");
    return new VectorIndex(
        indexId,
        parts[0],
        parts[1],
        4,
        DistanceMetric.COSINE,
        engineParams == null ? "" : engineParams,
        Instant.parse("2026-04-28T00:00:00Z"));
  }

  private static VectorIndex indexWithParams(String indexId, IndexBuildParams params) {
    return indexWithRawJson(indexId, params.toJson());
  }

  private static final class StubRepo implements VectorIndexRepository {

    private final Map<String, VectorIndex> byId = new HashMap<>();
    final AtomicInteger findByIdInvocations = new AtomicInteger();

    void register(VectorIndex index) {
      byId.put(index.indexId(), index);
    }

    @Override
    public VectorIndex create(VectorIndex index) {
      byId.put(index.indexId(), index);
      return index;
    }

    @Override
    public Optional<VectorIndex> findById(String indexId) {
      findByIdInvocations.incrementAndGet();
      return Optional.ofNullable(byId.get(indexId));
    }

    @Override
    public List<VectorIndex> listByBucket(String bucketId) {
      return byId.values().stream().filter(v -> v.bucketId().equals(bucketId)).toList();
    }

    @Override
    public List<VectorIndex> listAll() {
      return List.copyOf(byId.values());
    }

    @Override
    public void delete(String indexId) {
      byId.remove(indexId);
    }
  }
}
