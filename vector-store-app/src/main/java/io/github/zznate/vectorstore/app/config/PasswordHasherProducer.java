package io.github.zznate.vectorstore.app.config;

import io.github.zznate.vectorstore.api.auth.Argon2PasswordHasher;
import io.github.zznate.vectorstore.api.auth.PasswordHasher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds the application's {@link PasswordHasher} from the
 * {@code vectorstore.auth.argon2.*} configuration block so deployments can
 * retune memory/iterations/parallelism without a code change.
 */
@ApplicationScoped
public class PasswordHasherProducer {

  @ConfigProperty(name = "vectorstore.auth.argon2.iterations")
  int iterations;

  @ConfigProperty(name = "vectorstore.auth.argon2.memory-kib")
  int memoryKib;

  @ConfigProperty(name = "vectorstore.auth.argon2.parallelism")
  int parallelism;

  @Produces
  @Singleton
  public PasswordHasher passwordHasher() {
    return new Argon2PasswordHasher(iterations, memoryKib, parallelism);
  }
}
