package io.github.zznate.vectorstore.engine.commit;

/**
 * Raised when the commit path finds nothing to commit — the write buffer
 * for the target index was empty at snapshot time. Callers translate this
 * to an HTTP 409 (conflict) response with a descriptive message.
 */
public class EmptyCommitException extends CommitFailedException {

  public EmptyCommitException(String indexId) {
    super("empty", new IllegalStateException("write buffer empty for index " + indexId));
  }
}
