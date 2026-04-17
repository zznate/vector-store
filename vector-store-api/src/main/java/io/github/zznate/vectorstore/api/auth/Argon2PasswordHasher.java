package io.github.zznate.vectorstore.api.auth;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;

/**
 * Argon2id implementation of {@link PasswordHasher} backed by
 * {@code password4j} (pure Java, no JNI).
 *
 * <p>Parameters are constructor-supplied so tests can run with cheap settings
 * and production can tune memory / iterations per deployment.
 */
public final class Argon2PasswordHasher implements PasswordHasher {

  /** Bytes of random salt per hash. 16 matches OWASP guidance. */
  private static final int SALT_LENGTH_BYTES = 16;

  /** Bytes of derived key. 32 matches OWASP guidance. */
  private static final int OUTPUT_LENGTH_BYTES = 32;

  /** Argon2 version constant — 0x13 (19) is the current spec revision. */
  private static final int ARGON2_VERSION = Argon2Function.ARGON2_VERSION_13;

  private final Argon2Function argon2;

  public Argon2PasswordHasher(int iterations, int memoryKib, int parallelism) {
    this.argon2 =
        Argon2Function.getInstance(
            memoryKib,
            iterations,
            parallelism,
            OUTPUT_LENGTH_BYTES,
            Argon2.ID,
            ARGON2_VERSION);
  }

  @Override
  public String hash(String secret) {
    return Password.hash(secret).addRandomSalt(SALT_LENGTH_BYTES).with(argon2).getResult();
  }

  @Override
  public boolean verify(String secret, String hash) {
    return Password.check(secret, hash).with(argon2);
  }
}
