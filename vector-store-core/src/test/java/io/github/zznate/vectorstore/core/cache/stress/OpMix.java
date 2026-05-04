package io.github.zznate.vectorstore.core.cache.stress;

/**
 * Operation distribution as percentages summing to 100. Workers roll a
 * uniform integer in {@code [0, 100)} and {@link #pick(int)} maps the roll
 * onto an {@link Op}, so the distribution is driven from a seeded RNG.
 */
public record OpMix(int getPct, int putPct, int invalidatePct) {

  public OpMix {
    if (getPct + putPct + invalidatePct != 100) {
      throw new IllegalArgumentException(
          "op mix must sum to 100: get="
              + getPct
              + " put="
              + putPct
              + " invalidate="
              + invalidatePct);
    }
    if (getPct < 0 || putPct < 0 || invalidatePct < 0) {
      throw new IllegalArgumentException("op mix percentages must be non-negative");
    }
  }

  public Op pick(int roll) {
    if (roll < getPct) {
      return Op.GET;
    }
    if (roll < getPct + putPct) {
      return Op.PUT;
    }
    return Op.INVALIDATE;
  }

  public enum Op {
    GET,
    PUT,
    INVALIDATE
  }
}
