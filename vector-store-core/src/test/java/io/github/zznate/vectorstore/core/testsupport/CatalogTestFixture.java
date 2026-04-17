package io.github.zznate.vectorstore.core.testsupport;

import io.github.zznate.vectorstore.core.catalog.jdbi.JdbiConfigurer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.sqlite.SQLiteDataSource;

/**
 * Builds a fresh file-backed SQLite catalog per test class, runs Flyway, and
 * exposes a configured {@link Jdbi}. Each instance owns a unique temp file so
 * tests never contend over an in-memory shared database.
 *
 * <p>File-backed rather than in-memory because SQLite's {@code :memory:} URL
 * gives a distinct database to every JDBC connection; Flyway's migration
 * connection would see a different database than the {@link Jdbi} under test.
 */
public final class CatalogTestFixture implements AutoCloseable {

  private final Path dbFile;
  private final Jdbi jdbi;

  public CatalogTestFixture() {
    try {
      this.dbFile = Files.createTempFile("vector-store-test-", ".db");
      Files.deleteIfExists(dbFile);

      DataSource dataSource = newDataSource(dbFile);
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .load()
          .migrate();

      this.jdbi = JdbiConfigurer.configure(Jdbi.create(dataSource));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Jdbi jdbi() {
    return jdbi;
  }

  @Override
  public void close() throws IOException {
    Files.deleteIfExists(dbFile);
  }

  private static DataSource newDataSource(Path dbFile) {
    SQLiteDataSource ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath() + "?foreign_keys=on");
    return ds;
  }
}
