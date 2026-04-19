package io.github.zznate.vectorstore.datagen.embed;

import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import java.io.IOException;

/**
 * {@link Embedder} backed by {@code sentence-transformers/all-MiniLM-L6-v2}
 * through DJL's HuggingFace PyTorch loader. 384-dimensional embeddings,
 * Apache-2.0 model, ~80 MB of weights + ~400 MB of PyTorch native libs
 * cached under {@code ~/.djl.ai/} on first run.
 *
 * <p>Phase 2 test data uses these embeddings directly. Production-deployment
 * choice of model is unrelated; the vector-store service itself knows
 * nothing about model provenance.
 */
public final class MiniLmEmbedder implements Embedder {

  public static final String MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2";
  public static final int DIMENSION = 384;

  private final ZooModel<String, float[]> model;
  private final Predictor<String, float[]> predictor;

  public MiniLmEmbedder() throws ModelException, IOException {
    Criteria<String, float[]> criteria =
        Criteria.builder()
            .setTypes(String.class, float[].class)
            .optModelUrls("djl://ai.djl.huggingface.pytorch/" + MODEL_ID)
            .optEngine("PyTorch")
            .build();
    this.model = criteria.loadModel();
    this.predictor = model.newPredictor();
  }

  @Override
  public float[] embed(String text) {
    try {
      return predictor.predict(text);
    } catch (TranslateException e) {
      throw new RuntimeException("embedding failed for text starting with '"
          + text.substring(0, Math.min(60, text.length())) + "...'", e);
    }
  }

  @Override
  public int dimension() {
    return DIMENSION;
  }

  @Override
  public String modelId() {
    return MODEL_ID;
  }

  @Override
  public void close() {
    predictor.close();
    model.close();
  }
}
