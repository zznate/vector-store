package io.github.zznate.vectorstore.datagen;

import io.github.zznate.vectorstore.datagen.fixture.GenerateRecallFixtureCommand;
import io.github.zznate.vectorstore.datagen.fixture.ValidateFixtureCommand;
import java.util.Arrays;

/**
 * Tiny subcommand dispatch for the datagen module. Every subcommand
 * corresponds to one {@code exec-maven-plugin} execution declared in
 * {@code pom.xml}; direct invocation from the shell is also supported via
 * {@code java -jar vector-store-datagen.jar <subcommand> ...}.
 */
public final class Cli {

  private Cli() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      System.exit(1);
    }
    String command = args[0];
    String[] rest = Arrays.copyOfRange(args, 1, args.length);
    switch (command) {
      case "generate-recall-fixture" -> GenerateRecallFixtureCommand.run(rest);
      case "validate-fixture" -> ValidateFixtureCommand.run(rest);
      case "help", "--help", "-h" -> usage();
      default -> {
        System.err.println("unknown subcommand: " + command);
        usage();
        System.exit(2);
      }
    }
  }

  private static void usage() {
    System.err.println(
        """
        vector-store-datagen — tooling for recall-test fixtures and demo data.

        Usage:
          <cmd> generate-recall-fixture --output <dir>
                  Fetch the pinned Wikipedia ML corpus, chunk, embed, and
                  write corpus.jsonl + queries.jsonl + README.md into the
                  output directory.

          <cmd> validate-fixture --input <dir>
                  Sanity-check a previously-written fixture: every entry
                  parses, embedding dimensions agree, query
                  expectedArticleSlug values all exist in the corpus.

          <cmd> help
                  Print this message.
        """);
  }
}
