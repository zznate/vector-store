package io.github.zznate.vectorstore.core.catalog.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.zznate.vectorstore.core.cache.CachePolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexBuildParamsTest {

  @Test
  void defaultsAreSmartAndUnboundedCacheBytes() {
    IndexBuildParams defaults = IndexBuildParams.defaults();
    assertThat(defaults.cachePolicy()).isEqualTo(CachePolicy.SMART);
    assertThat(defaults.cacheBytes()).isNull();
  }

  @Test
  void defaultsRoundTripThroughJson() {
    IndexBuildParams original = IndexBuildParams.defaults();
    IndexBuildParams parsed = IndexBuildParams.fromJson(original.toJson());
    assertThat(parsed).isEqualTo(original);
  }

  @Test
  void legacyJsonWithoutCachePolicyDeserializesToSmart() {
    String legacy =
        "{\"m\":32,\"beamWidth\":200,\"neighborOverflow\":1.2,\"alpha\":1.2,"
            + "\"pqSubspaces\":128,\"pqSubspaceClusters\":256,\"addHierarchy\":false}";
    IndexBuildParams parsed = IndexBuildParams.fromJson(legacy);
    assertThat(parsed.cachePolicy()).isEqualTo(CachePolicy.SMART);
    assertThat(parsed.cacheBytes()).isNull();
  }

  @Test
  void residentPolicyRoundTrips() {
    IndexBuildParams resident =
        new IndexBuildParams(32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.RESIDENT, null);
    IndexBuildParams parsed = IndexBuildParams.fromJson(resident.toJson());
    assertThat(parsed.cachePolicy()).isEqualTo(CachePolicy.RESIDENT);
  }

  @Test
  void cacheBytesOverrideRoundTrips() {
    IndexBuildParams capped =
        new IndexBuildParams(32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.MINIMAL, 12_345L);
    IndexBuildParams parsed = IndexBuildParams.fromJson(capped.toJson());
    assertThat(parsed.cachePolicy()).isEqualTo(CachePolicy.MINIMAL);
    assertThat(parsed.cacheBytes()).isEqualTo(12_345L);
  }

  @Test
  void canonicalJsonOmitsNullCacheBytes() {
    String json = IndexBuildParams.defaults().toJson();
    assertThat(json).doesNotContain("cacheBytes");
  }

  @Test
  void negativeCacheBytesRejected() {
    assertThatThrownBy(
            () ->
                new IndexBuildParams(
                    32, 200, 1.2f, 1.2f, 128, 256, false, CachePolicy.SMART, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cacheBytes");
  }

  @Test
  void overridesCanFlipPolicyAndCacheBytes() {
    Map<String, Object> overrides = new LinkedHashMap<>();
    overrides.put("cachePolicy", "RESIDENT");
    overrides.put("cacheBytes", 64_000L);
    IndexBuildParams merged = IndexBuildParams.fromOverrides(overrides);
    assertThat(merged.cachePolicy()).isEqualTo(CachePolicy.RESIDENT);
    assertThat(merged.cacheBytes()).isEqualTo(64_000L);
    assertThat(merged.m()).isEqualTo(32);
    assertThat(merged.beamWidth()).isEqualTo(200);
  }

  @Test
  void overridesIgnoreUnknownKeys() {
    Map<String, Object> overrides = Map.of("totallyUnknownKey", "ignored", "m", 16);
    IndexBuildParams merged = IndexBuildParams.fromOverrides(overrides);
    assertThat(merged.m()).isEqualTo(16);
    assertThat(merged.cachePolicy()).isEqualTo(CachePolicy.SMART);
  }

  @Test
  void invalidPqSubspaceClustersRejected() {
    assertThatThrownBy(
            () ->
                new IndexBuildParams(
                    32, 200, 1.2f, 1.2f, 128, 257, false, CachePolicy.SMART, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pqSubspaceClusters");
  }

  @Test
  void blankJsonReturnsDefaults() {
    assertThat(IndexBuildParams.fromJson("")).isEqualTo(IndexBuildParams.defaults());
    assertThat(IndexBuildParams.fromJson(null)).isEqualTo(IndexBuildParams.defaults());
  }

  @Test
  void defaultsFromConfigBuildsFromMappingValues() {
    IndexBuildParamsDefaults config =
        new StubDefaults(64, 400, 1.5f, 1.4f, 64, 128, true, CachePolicy.RESIDENT);
    IndexBuildParams params = IndexBuildParams.defaults(config);
    assertThat(params.m()).isEqualTo(64);
    assertThat(params.beamWidth()).isEqualTo(400);
    assertThat(params.neighborOverflow()).isEqualTo(1.5f);
    assertThat(params.alpha()).isEqualTo(1.4f);
    assertThat(params.pqSubspaces()).isEqualTo(64);
    assertThat(params.pqSubspaceClusters()).isEqualTo(128);
    assertThat(params.addHierarchy()).isTrue();
    assertThat(params.cachePolicy()).isEqualTo(CachePolicy.RESIDENT);
    assertThat(params.cacheBytes()).isNull();
  }

  @Test
  void fromOverridesWithExplicitBaseLayersOverridesOnTop() {
    IndexBuildParams base =
        new IndexBuildParams(64, 400, 1.5f, 1.4f, 64, 128, true, CachePolicy.RESIDENT, null);
    Map<String, Object> overrides = Map.of("m", 16, "addHierarchy", false);
    IndexBuildParams merged = IndexBuildParams.fromOverrides(overrides, base);
    assertThat(merged.m()).isEqualTo(16);
    assertThat(merged.addHierarchy()).isFalse();
    assertThat(merged.beamWidth()).isEqualTo(400);
    assertThat(merged.cachePolicy()).isEqualTo(CachePolicy.RESIDENT);
  }

  @Test
  void fromOverridesWithEmptyMapReturnsBaseVerbatim() {
    IndexBuildParams base =
        new IndexBuildParams(64, 400, 1.5f, 1.4f, 64, 128, true, CachePolicy.RESIDENT, null);
    assertThat(IndexBuildParams.fromOverrides(Map.of(), base)).isEqualTo(base);
    assertThat(IndexBuildParams.fromOverrides(null, base)).isEqualTo(base);
  }

  /** Plain-Java stub of the SmallRye {@link IndexBuildParamsDefaults} mapping. */
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
}
