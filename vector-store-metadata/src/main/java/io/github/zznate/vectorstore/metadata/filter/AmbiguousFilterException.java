package io.github.zznate.vectorstore.metadata.filter;

import java.util.Set;

/**
 * Raised when a filter payload combines operators in a way whose
 * precedence would be ambiguous. The canonical case is a top-level
 * {@code $or} key alongside other sibling keys: the wire format does not
 * specify whether sibling clauses bind inside or outside the disjunction,
 * so we reject the payload rather than guess.
 *
 * <p>Plain-Java exception so the metadata module stays free of API-layer
 * coupling. The API translates this to a {@code 400 bad_request} envelope.
 */
public class AmbiguousFilterException extends RuntimeException {

  private final String operator;
  private final Set<String> siblings;

  public AmbiguousFilterException(String operator, Set<String> siblings) {
    super(
        "ambiguous filter precedence: top-level '"
            + operator
            + "' cannot be combined with sibling keys "
            + siblings);
    this.operator = operator;
    this.siblings = Set.copyOf(siblings);
  }

  public String operator() {
    return operator;
  }

  public Set<String> siblings() {
    return siblings;
  }
}
