package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

/**
 * Root of the vector-store exception hierarchy. Every HTTP error the service
 * returns is an instance of a permitted subclass; the exception mapper reads
 * {@link #status()} and {@link #errorCode()} to build the response.
 *
 * <p>Sealed so the set of error outcomes is closed and visible in one place.
 */
public sealed class VectorStoreException extends RuntimeException
    permits BucketAlreadyExistsException,
        BucketNotEmptyException,
        BucketNotFoundException,
        CommitFailedHttpException,
        DimensionMismatchException,
        EmptyCommitHttpException,
        ForbiddenException,
        IndexAlreadyExistsException,
        IndexNotFoundException,
        NotImplementedException,
        UnauthorizedException,
        UnsupportedFilterOperatorHttpException {

  private final Status status;
  private final String errorCode;

  protected VectorStoreException(Status status, String errorCode, String message) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
  }

  public Status status() {
    return status;
  }

  public String errorCode() {
    return errorCode;
  }
}
