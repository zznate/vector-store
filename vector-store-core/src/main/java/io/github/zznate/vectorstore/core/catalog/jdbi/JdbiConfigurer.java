package io.github.zznate.vectorstore.core.catalog.jdbi;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.CaseInsensitiveColumnNameMatcher;
import org.jdbi.v3.core.mapper.reflect.ReflectionMappers;
import org.jdbi.v3.core.mapper.reflect.SnakeCaseColumnNameMatcher;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * Single source of truth for how a {@link Jdbi} instance must be configured
 * before the vector-store catalog DAOs can use it.
 *
 * <p>The {@code vector-store-app} runtime producer and the in-module tests
 * both call {@link #configure(Jdbi)} so mapping rules never diverge between
 * production and test paths.
 */
public final class JdbiConfigurer {

  private JdbiConfigurer() {}

  /**
   * Installs {@code SqlObjectPlugin} and configures {@code ConstructorMapper}
   * to match snake_case column names against camelCase record components.
   */
  public static Jdbi configure(Jdbi jdbi) {
    jdbi.installPlugin(new SqlObjectPlugin());
    jdbi.getConfig(ReflectionMappers.class)
        .setColumnNameMatchers(
            List.of(new CaseInsensitiveColumnNameMatcher(), new SnakeCaseColumnNameMatcher()));
    return jdbi;
  }
}
