package io.github.zznate.vectorstore.engine.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SearchTuningTest {

  @Test
  void defaultsReturnsTopKAndZeroFloors() {
    SearchTuning tuning = SearchTuning.defaults(10);
    assertThat(tuning.rerankK()).isEqualTo(10);
    assertThat(tuning.threshold()).isZero();
    assertThat(tuning.rerankFloor()).isZero();
  }

  @Test
  void constructorRejectsRerankKBelowOne() {
    assertThatThrownBy(() -> new SearchTuning(0, 0.0f, 0.0f))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rerankK");
  }

  @Test
  void constructorRejectsNegativeThreshold() {
    assertThatThrownBy(() -> new SearchTuning(10, -0.1f, 0.0f))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threshold");
  }

  @Test
  void constructorRejectsNegativeRerankFloor() {
    assertThatThrownBy(() -> new SearchTuning(10, 0.0f, -0.1f))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rerankFloor");
  }

  @Test
  void withRerankKNullReturnsSameInstance() {
    SearchTuning original = SearchTuning.defaults(10);
    assertThat(original.withRerankK(null)).isSameAs(original);
  }

  @Test
  void withRerankKValueOverridesField() {
    SearchTuning tuning = SearchTuning.defaults(10).withRerankK(50);
    assertThat(tuning.rerankK()).isEqualTo(50);
    assertThat(tuning.threshold()).isZero();
    assertThat(tuning.rerankFloor()).isZero();
  }

  @Test
  void withThresholdNullReturnsSameInstance() {
    SearchTuning original = SearchTuning.defaults(10);
    assertThat(original.withThreshold(null)).isSameAs(original);
  }

  @Test
  void withThresholdValueOverridesField() {
    SearchTuning tuning = SearchTuning.defaults(10).withThreshold(0.85f);
    assertThat(tuning.rerankK()).isEqualTo(10);
    assertThat(tuning.threshold()).isEqualTo(0.85f);
    assertThat(tuning.rerankFloor()).isZero();
  }

  @Test
  void withRerankFloorNullReturnsSameInstance() {
    SearchTuning original = SearchTuning.defaults(10);
    assertThat(original.withRerankFloor(null)).isSameAs(original);
  }

  @Test
  void withRerankFloorValueOverridesField() {
    SearchTuning tuning = SearchTuning.defaults(10).withRerankFloor(0.5f);
    assertThat(tuning.rerankK()).isEqualTo(10);
    assertThat(tuning.threshold()).isZero();
    assertThat(tuning.rerankFloor()).isEqualTo(0.5f);
  }

  @Test
  void chainedWithSettersComposeIndependently() {
    SearchTuning tuning =
        SearchTuning.defaults(10).withRerankK(50).withThreshold(0.85f).withRerankFloor(0.5f);
    assertThat(tuning.rerankK()).isEqualTo(50);
    assertThat(tuning.threshold()).isEqualTo(0.85f);
    assertThat(tuning.rerankFloor()).isEqualTo(0.5f);
  }

  @Test
  void chainedWithAllNullsEqualsDefaults() {
    SearchTuning tuning =
        SearchTuning.defaults(10).withRerankK(null).withThreshold(null).withRerankFloor(null);
    assertThat(tuning).isEqualTo(SearchTuning.defaults(10));
  }

  @Test
  void withSettersValidateOverriddenValues() {
    assertThatThrownBy(() -> SearchTuning.defaults(10).withRerankK(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rerankK");
    assertThatThrownBy(() -> SearchTuning.defaults(10).withThreshold(-0.1f))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threshold");
    assertThatThrownBy(() -> SearchTuning.defaults(10).withRerankFloor(-0.1f))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rerankFloor");
  }
}
