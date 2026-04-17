package io.github.zznate.vectorstore.app.startup;

import io.github.zznate.vectorstore.api.auth.PasswordHasher;
import io.github.zznate.vectorstore.core.catalog.model.ApiKey;
import io.github.zznate.vectorstore.core.catalog.repository.ApiKeyRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On first boot, if the environment variable
 * {@code VECTORSTORE_BOOTSTRAP_ADMIN_KEY} is set and no admin key yet exists
 * in the catalog, hashes the secret and seeds a single admin API key from
 * its value.
 *
 * <p>The env var must carry the full {@code keyId.secret} token — the same
 * form the client sends in {@code X-Api-Key}. Migrations cannot read env
 * vars safely, which is why this lives in a {@code @Startup} bean rather
 * than in a Flyway migration.
 */
@ApplicationScoped
public class AdminKeyBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(AdminKeyBootstrap.class);

  private final ApiKeyRepository apiKeys;
  private final PasswordHasher hasher;
  private final Clock clock;

  @ConfigProperty(name = "vectorstore.bootstrap.admin-key")
  Optional<String> bootstrapAdminKey;

  @Inject
  public AdminKeyBootstrap(ApiKeyRepository apiKeys, PasswordHasher hasher, Clock clock) {
    this.apiKeys = apiKeys;
    this.hasher = hasher;
    this.clock = clock;
  }

  void onStart(@Observes StartupEvent event) {
    String token = bootstrapAdminKey.orElse("");
    if (token.isBlank()) {
      return;
    }
    if (apiKeys.adminKeyExists()) {
      LOG.info("Bootstrap admin key ignored: an admin key already exists in the catalog");
      return;
    }
    int dot = token.indexOf('.');
    if (dot <= 0 || dot == token.length() - 1) {
      LOG.warn(
          "Bootstrap admin key ignored: VECTORSTORE_BOOTSTRAP_ADMIN_KEY must be 'keyId.secret'");
      return;
    }
    String keyId = token.substring(0, dot);
    String secret = token.substring(dot + 1);
    apiKeys.create(new ApiKey(keyId, hasher.hash(secret), null, clock.instant(), null));
    LOG.info("Bootstrap admin key created: keyId={}", keyId);
  }
}
