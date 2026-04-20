package io.github.zznate.vectorstore.metadata.filter;

/**
 * Raised when a filter payload contains an operator that is outside the
 * phase-1 grammar (anything other than plain equality). The API layer
 * catches this and translates it into a {@code 400 Bad Request} with the
 * {@code unsupported_operator} error code and the offending key / operator
 * in the message, per the phase-1 error envelope.
 */
public class UnsupportedFilterOperatorException extends RuntimeException {

  private final String key;
  private final String operator;

  public UnsupportedFilterOperatorException(String key, String operator) {
    super("unsupported filter operator '" + operator + "' on key '" + key + "'");
    this.key = key;
    this.operator = operator;
  }

  public String key() {
    return key;
  }

  public String operator() {
    return operator;
  }
}
