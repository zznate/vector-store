package io.github.zznate.vectorstore.datagen.embed;

import java.util.List;

/**
 * Produces a fixed-dimension embedding for a string of text. One method
 * because the datagen pipeline never batches — simple, predictable, easy
 * to swap.
 */
public interface Embedder extends AutoCloseable {

  float[] embed(String text);

  default List<float[]> embedAll(List<String> texts) {
    return texts.stream().map(this::embed).toList();
  }

  int dimension();

  String modelId();
}
