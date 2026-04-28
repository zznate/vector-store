package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;
import java.util.Set;

/**
 * Thrown when a query's {@code filter} payload combines operators with
 * ambiguous precedence — typically a top-level {@code $or} alongside
 * other sibling keys. Maps to {@code 400 Bad Request} with the error
 * code {@code bad_request} and a message naming the offending operator
 * so clients can disambiguate by restructuring the payload.
 */
public final class AmbiguousFilterHttpException extends VectorStoreException {

  public AmbiguousFilterHttpException(String operator, Set<String> siblings) {
    super(
        Status.BAD_REQUEST,
        "bad_request",
        "filter operator '"
            + operator
            + "' has ambiguous precedence with sibling keys "
            + siblings
            + "; nest siblings inside the operator's clauses or remove them");
  }
}
