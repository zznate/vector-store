package io.github.zznate.vectorstore.api.error;

import jakarta.ws.rs.core.Response.Status;

/**
 * Thrown when a query's {@code filter} payload carries an operator outside
 * the supported equality grammar. Maps to {@code 400 Bad Request} with
 * the error code {@code unsupported_operator} and a message that names
 * the offending key and operator so clients can localise the fix.
 */
public final class UnsupportedFilterOperatorHttpException extends VectorStoreException {

  public UnsupportedFilterOperatorHttpException(String key, String operator) {
    super(
        Status.BAD_REQUEST,
        "unsupported_operator",
        "filter operator '" + operator + "' on key '" + key + "' is not supported");
  }
}
