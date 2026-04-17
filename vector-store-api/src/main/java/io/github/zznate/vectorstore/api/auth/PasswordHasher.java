package io.github.zznate.vectorstore.api.auth;

/**
 * Hashes and verifies API-key secrets. The concrete implementation is
 * produced as a CDI bean by the app module, parameterised against
 * application configuration; tests construct an implementation directly.
 */
public interface PasswordHasher {

  String hash(String secret);

  boolean verify(String secret, String hash);
}
