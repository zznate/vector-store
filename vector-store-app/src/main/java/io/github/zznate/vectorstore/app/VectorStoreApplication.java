package io.github.zznate.vectorstore.app;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Explicit Quarkus entrypoint. Without this, Quarkus generates one by
 * default; declaring it makes the application lifecycle visible in stack
 * traces and tests.
 */
@QuarkusMain
public class VectorStoreApplication implements QuarkusApplication {

  public static void main(String[] args) {
    Quarkus.run(VectorStoreApplication.class, args);
  }

  @Override
  public int run(String... args) {
    Quarkus.waitForExit();
    return 0;
  }
}
