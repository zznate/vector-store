package io.github.zznate.vectorstore.metadata.filter;

import java.util.List;
import java.util.Set;

/**
 * AST of the filter grammar. Sealed so the {@link FilterCompiler} can
 * evaluate via pattern matching without an {@code instanceof} ladder and
 * the compiler catches any future-added variant that forgets an evaluation
 * branch.
 *
 * <p>Variants:
 * <ul>
 *   <li>{@link Equals} — leaf: attribute key equals a string value.
 *   <li>{@link In} — leaf: attribute key matches any of a finite set of
 *       string values; conceptually {@code OR(Equals(key, v) for v in values)}
 *       but kept as a distinct variant so the compiler can resolve it
 *       against a posting-list strategy in one bitmap union.
 *   <li>{@link And} — N-ary conjunction.
 *   <li>{@link Or} — N-ary disjunction.
 *   <li>{@link Not} — unary negation.
 * </ul>
 *
 * <p>Numeric range operators ({@code $gt}, {@code $lt}, ...) and typed
 * attributes are not modelled here; the parser rejects them at parse time
 * with {@code unsupported_operator}.
 */
public sealed interface FilterExpr
    permits FilterExpr.Equals, FilterExpr.In, FilterExpr.And, FilterExpr.Or, FilterExpr.Not {

  /** Number of leaf terms. Drives the {@code term_count} meter tag. */
  int termCount();

  record Equals(String key, String value) implements FilterExpr {
    @Override
    public int termCount() {
      return 1;
    }
  }

  record In(String key, Set<String> values) implements FilterExpr {
    public In {
      values = Set.copyOf(values);
    }

    @Override
    public int termCount() {
      return 1;
    }
  }

  record And(List<FilterExpr> terms) implements FilterExpr {
    public And {
      terms = List.copyOf(terms);
    }

    @Override
    public int termCount() {
      return terms.stream().mapToInt(FilterExpr::termCount).sum();
    }
  }

  record Or(List<FilterExpr> terms) implements FilterExpr {
    public Or {
      terms = List.copyOf(terms);
    }

    @Override
    public int termCount() {
      return terms.stream().mapToInt(FilterExpr::termCount).sum();
    }
  }

  record Not(FilterExpr term) implements FilterExpr {
    @Override
    public int termCount() {
      return term.termCount();
    }
  }
}
