package io.github.zznate.vectorstore.core.cache;

import java.util.Optional;

/**
 * Byte-oriented cache tier suitable for off-heap, mmap'd disk, and future
 * remote providers. Distinct from {@link CacheTier} because these tiers
 * cannot hold live object references — every value crosses the tier
 * boundary as a byte sequence.
 *
 * <p>Contract strengthened beyond {@link CacheTier}:
 * <ul>
 *   <li>Byte budget is authoritative: an over-budget {@code put} evicts
 *       existing entries synchronously until the new value fits.
 *   <li>{@link #close()} releases every native / off-heap resource the
 *       provider holds — memory arenas, mmap'd buffers, file locks — so a
 *       clean shutdown leaves no leaks.
 *   <li>{@code get} returns a byte[] copy of the cached payload; the
 *       caller owns the returned array. Implementations may revisit this
 *       for zero-copy reads in a future iteration.
 * </ul>
 */
public interface L2Provider extends AutoCloseable {

  Optional<byte[]> get(String key);

  void put(String key, byte[] bytes);

  void invalidate(String key);

  void invalidateAll();

  CacheTierStats stats();

  String tierName();

  @Override
  void close();
}
