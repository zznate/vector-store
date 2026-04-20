package io.github.zznate.vectorstore.metadata.filter;

import java.util.List;

/**
 * AST of the phase-1 filter grammar. Only {@link Equals} and {@link And}
 * are supported; {@code $in}, {@code $or}, and range operators are
 * reserved for phase 2.
 *
 * <p>Sealed so the {@link FilterCompiler} can evaluate via pattern matching
 * without an {@code instanceof} ladder and the compiler catches any
 * future-added variant that forgets an evaluation branch.
 */
public sealed interface FilterExpr permits FilterExpr.Equals, FilterExpr.And {

  /** Number of leaf terms. Drives the {@code term_count} meter tag. */
  int termCount();

  record Equals(String key, String value) implements FilterExpr {
    @Override
    public int termCount() {
      return 1;
    }
  }

  record And(List<FilterExpr> terms) implements FilterExpr {
    public And(List<FilterExpr> terms) {
      this.terms = List.copyOf(terms);
    }

    @Override
    public int termCount() {
      return terms.stream().mapToInt(FilterExpr::termCount).sum();
    }
  }
}
