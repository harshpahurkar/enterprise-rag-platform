package com.harshpahurkar.rag.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;

@Configuration
public class AiConfig {

	/** Must match vector(384) in V1__schema.sql. */
	public static final int EMBEDDING_DIMENSION = 384;

	/** Runs inside the JVM (ONNX): no network hop per query, and documents never leave the server. */
	@Bean
	EmbeddingModel embeddingModel() {
		EmbeddingModel model = new BgeSmallEnV15QuantizedEmbeddingModel();
		if (model.dimension() != EMBEDDING_DIMENSION) {
			throw new IllegalStateException(
					"Embedding model dimension " + model.dimension() + " != schema " + EMBEDDING_DIMENSION);
		}
		return model;
	}

}
