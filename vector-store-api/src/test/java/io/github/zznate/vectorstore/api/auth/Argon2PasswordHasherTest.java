package io.github.zznate.vectorstore.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Argon2PasswordHasherTest {

  private final PasswordHasher hasher = new Argon2PasswordHasher(1, 1024, 1);

  @Test
  void hashThenVerifyRoundTripsForMatchingSecret() {
    String hash = hasher.hash("correct-secret");

    assertThat(hasher.verify("correct-secret", hash)).isTrue();
  }

  @Test
  void verifyRejectsDifferentSecret() {
    String hash = hasher.hash("correct-secret");

    assertThat(hasher.verify("wrong-secret", hash)).isFalse();
  }

  @Test
  void hashingSameSecretTwiceProducesDifferentHashes() {
    String hash1 = hasher.hash("same-secret");
    String hash2 = hasher.hash("same-secret");

    assertThat(hash1).isNotEqualTo(hash2);
    assertThat(hasher.verify("same-secret", hash1)).isTrue();
    assertThat(hasher.verify("same-secret", hash2)).isTrue();
  }

  @Test
  void hashesUseArgon2idAlgorithm() {
    String hash = hasher.hash("any-secret");

    assertThat(hash).startsWith("$argon2id$");
  }
}
