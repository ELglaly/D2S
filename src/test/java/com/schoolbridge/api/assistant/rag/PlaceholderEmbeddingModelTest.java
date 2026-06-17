package com.schoolbridge.api.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaceholderEmbeddingModelTest {

  private final PlaceholderEmbeddingModel model = new PlaceholderEmbeddingModel(768);

  @Test
  void reportsConfiguredDimensions() {
    assertThat(model.dimensions()).isEqualTo(768);
    assertThat(model.embed("anything")).hasSize(768);
  }

  @Test
  void isDeterministicForEqualText() {
    assertThat(model.embed("الواجب المنزلي")).containsExactly(model.embed("الواجب المنزلي"));
  }

  @Test
  void differsForDifferentText() {
    assertThat(model.embed("alpha")).isNotEqualTo(model.embed("beta"));
  }

  @Test
  void producesUnitVector() {
    float[] v = model.embed("normalize me");
    double sumSquares = 0;
    for (float f : v) {
      sumSquares += (double) f * f;
    }
    assertThat(Math.sqrt(sumSquares)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
  }
}
