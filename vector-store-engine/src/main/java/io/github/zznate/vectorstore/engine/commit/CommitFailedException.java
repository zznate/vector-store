package io.github.zznate.vectorstore.engine.commit;

/**
 * Thrown by {@link CommitCoordinator#commit} when the commit pipeline fails
 * at any phase. The {@code phase} field tells the caller which step broke
 * (the same tag value the {@code vectorstore.commit.failures} counter
 * carries).
 */
public class CommitFailedException extends Exception {

  private final String phase;

  public CommitFailedException(String phase, Throwable cause) {
    super("commit failed during phase " + phase + ": " + cause.getMessage(), cause);
    this.phase = phase;
  }

  public String phase() {
    return phase;
  }
}
