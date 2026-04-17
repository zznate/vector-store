package io.github.zznate.vectorstore.app.config;

import io.github.zznate.vectorstore.core.catalog.jdbi.JdbiConfigurer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;

/**
 * Produces the application's {@link Jdbi} instance, bound to the
 * Quarkus-managed Agroal {@link DataSource} and configured through
 * {@link JdbiConfigurer} — the single source of truth shared with
 * {@code vector-store-core}'s test fixtures.
 */
@ApplicationScoped
public class JdbiProducer {

  @Produces
  @Singleton
  public Jdbi jdbi(DataSource dataSource) {
    return JdbiConfigurer.configure(Jdbi.create(dataSource));
  }
}
