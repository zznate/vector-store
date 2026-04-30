package io.github.zznate.vectorstore.core.catalog.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zznate.vectorstore.core.cache.CachePolicy;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IndexParamsValidatorTest {

  private static final IndexBuildParamsDefaults CURRENT_DEFAULTS =
      new StubDefaults(32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.SMART);

  @Test
  void emptyRepositoryProducesEmptyReport() {
    IndexParamsValidator.ValidationReport report = validate();
    assertThat(report.drift()).isEmpty();
    assertThat(report.failures()).isEmpty();
    assertThat(report.currentDefaults()).isEqualTo(IndexBuildParams.defaults(CURRENT_DEFAULTS));
  }

  @Test
  void indexMatchingDefaultsReportsNoDrift() {
    InMemoryIndexRepo repo = new InMemoryIndexRepo();
    repo.add("demo/widgets", IndexBuildParams.defaults(CURRENT_DEFAULTS).toJson());
    IndexParamsValidator.ValidationReport report = validate(repo);
    assertThat(report.drift()).isEmpty();
    assertThat(report.failures()).isEmpty();
  }

  @Test
  void differentMOnAnyIndexProducesDrift() {
    IndexBuildParams persisted =
        new IndexBuildParams(64, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.SMART, null);
    InMemoryIndexRepo repo = new InMemoryIndexRepo();
    repo.add("demo/m-drifted", persisted.toJson());
    IndexParamsValidator.ValidationReport report = validate(repo);
    assertThat(report.drift()).hasSize(1);
    assertThat(report.drift().get(0).indexId()).isEqualTo("demo/m-drifted");
    assertThat(report.drift().get(0).persisted().m()).isEqualTo(64);
    assertThat(report.failures()).isEmpty();
  }

  @Test
  void differentAddHierarchyProducesDrift() {
    IndexBuildParams persisted =
        new IndexBuildParams(32, 200, 1.2f, 1.2f, 128, 256, true, CachePolicy.SMART, null);
    InMemoryIndexRepo repo = new InMemoryIndexRepo();
    repo.add("demo/hier-drifted", persisted.toJson());
    IndexParamsValidator.ValidationReport report = validate(repo);
    assertThat(report.drift()).hasSize(1);
    assertThat(report.drift().get(0).persisted().addHierarchy()).isTrue();
  }

  @Test
  void otherKnobsDriftingDoesNotProduceDriftEntry() {
    // beamWidth, alpha, neighborOverflow, pqSubspaces, etc. can drift
    // without preventing JVector compaction; the validator only flags
    // the load-bearing knobs.
    IndexBuildParams persisted =
        new IndexBuildParams(32, 400, 1.5f, 1.4f, 64, 128, false, CachePolicy.RESIDENT, null);
    InMemoryIndexRepo repo = new InMemoryIndexRepo();
    repo.add("demo/non-load-bearing", persisted.toJson());
    IndexParamsValidator.ValidationReport report = validate(repo);
    assertThat(report.drift()).isEmpty();
    assertThat(report.failures()).isEmpty();
  }

  @Test
  void malformedEngineParamsJsonProducesParseFailure() {
    InMemoryIndexRepo repo = new InMemoryIndexRepo();
    repo.add("demo/corrupt", "{this-is-not-valid-json");
    IndexParamsValidator.ValidationReport report = validate(repo);
    assertThat(report.drift()).isEmpty();
    assertThat(report.failures()).hasSize(1);
    assertThat(report.failures().get(0).indexId()).isEqualTo("demo/corrupt");
  }

  @Test
  void invariantViolatingPersistedParamsProducesParseFailure() {
    String invalid =
        "{\"m\":0,\"beamWidth\":200,\"neighborOverflow\":1.2,\"alpha\":1.2,"
            + "\"pqSubspaces\":128,\"pqSubspaceClusters\":256,\"addHierarchy\":false}";
    InMemoryIndexRepo repo = new InMemoryIndexRepo();
    repo.add("demo/invalid-m", invalid);
    IndexParamsValidator.ValidationReport report = validate(repo);
    assertThat(report.failures()).hasSize(1);
    assertThat(report.failures().get(0).message()).contains("m");
  }

  @Test
  void mixedReportContainsBothCategories() {
    InMemoryIndexRepo repo = new InMemoryIndexRepo();
    repo.add("demo/clean", IndexBuildParams.defaults(CURRENT_DEFAULTS).toJson());
    repo.add(
        "demo/m-drifted",
        new IndexBuildParams(64, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.SMART, null)
            .toJson());
    repo.add("demo/corrupt", "not-json");
    IndexParamsValidator.ValidationReport report = validate(repo);
    assertThat(report.drift()).extracting(IndexParamsValidator.Drift::indexId).containsExactly("demo/m-drifted");
    assertThat(report.failures())
        .extracting(IndexParamsValidator.ParseFailure::indexId)
        .containsExactly("demo/corrupt");
  }

  // --- helpers ---

  private static IndexParamsValidator.ValidationReport validate() {
    return validate(new InMemoryIndexRepo());
  }

  private static IndexParamsValidator.ValidationReport validate(InMemoryIndexRepo repo) {
    return new IndexParamsValidator(repo, CURRENT_DEFAULTS).validate();
  }

  private record StubDefaults(
      int m,
      int beamWidth,
      float neighborOverflow,
      float alpha,
      int pqSubspaces,
      int pqSubspaceClusters,
      boolean addHierarchy,
      CachePolicy cachePolicy)
      implements IndexBuildParamsDefaults {}

  /** In-memory VectorIndexRepository stub for unit testing. */
  private static final class InMemoryIndexRepo implements VectorIndexRepository {

    private final Map<String, VectorIndex> byId = new HashMap<>();

    void add(String indexId, String engineParamsJson) {
      String bucket = indexId.contains("/") ? indexId.substring(0, indexId.indexOf('/')) : indexId;
      String name = indexId.contains("/") ? indexId.substring(indexId.indexOf('/') + 1) : indexId;
      byId.put(
          indexId,
          VectorIndex.active(
              indexId,
              bucket,
              name,
              384,
              io.github.zznate.vectorstore.core.catalog.model.DistanceMetric.COSINE,
              engineParamsJson,
              Instant.EPOCH));
    }

    @Override
    public VectorIndex create(VectorIndex index) {
      byId.put(index.indexId(), index);
      return index;
    }

    @Override
    public Optional<VectorIndex> findById(String indexId) {
      return Optional.ofNullable(byId.get(indexId)).filter(v -> !v.isDeleted());
    }

    @Override
    public Optional<VectorIndex> findIncludingDeleted(String indexId) {
      return Optional.ofNullable(byId.get(indexId));
    }

    @Override
    public List<VectorIndex> listByBucket(String bucketId) {
      List<VectorIndex> out = new ArrayList<>();
      for (VectorIndex v : byId.values()) {
        if (v.bucketId().equals(bucketId) && !v.isDeleted()) {
          out.add(v);
        }
      }
      return out;
    }

    @Override
    public List<VectorIndex> listAll() {
      List<VectorIndex> out = new ArrayList<>();
      for (VectorIndex v : byId.values()) {
        if (!v.isDeleted()) {
          out.add(v);
        }
      }
      return out;
    }

    @Override
    public List<VectorIndex> listSoftDeletedBefore(Instant cutoff) {
      List<VectorIndex> out = new ArrayList<>();
      for (VectorIndex v : byId.values()) {
        if (v.isDeleted() && v.deletedAt().isBefore(cutoff)) {
          out.add(v);
        }
      }
      return out;
    }

    @Override
    public int countAnyByBucket(String bucketId) {
      int n = 0;
      for (VectorIndex v : byId.values()) {
        if (v.bucketId().equals(bucketId)) {
          n++;
        }
      }
      return n;
    }

    @Override
    public boolean softDelete(String indexId, Instant at) {
      VectorIndex existing = byId.get(indexId);
      if (existing == null || existing.isDeleted()) {
        return false;
      }
      byId.put(
          indexId,
          new VectorIndex(
              existing.indexId(),
              existing.bucketId(),
              existing.displayName(),
              existing.dimension(),
              existing.metric(),
              existing.engineParams(),
              existing.createdAt(),
              at));
      return true;
    }

    @Override
    public boolean restore(String indexId) {
      VectorIndex existing = byId.get(indexId);
      if (existing == null || !existing.isDeleted()) {
        return false;
      }
      byId.put(
          indexId,
          VectorIndex.active(
              existing.indexId(),
              existing.bucketId(),
              existing.displayName(),
              existing.dimension(),
              existing.metric(),
              existing.engineParams(),
              existing.createdAt()));
      return true;
    }

    @Override
    public void hardDelete(String indexId) {
      byId.remove(indexId);
    }
  }
}
