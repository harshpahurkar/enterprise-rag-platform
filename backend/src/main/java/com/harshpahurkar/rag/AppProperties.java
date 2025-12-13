package com.harshpahurkar.rag;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under {@code app.*} in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Rag rag, Llm llm, Demo demo) {

	public record Jwt(String secret, Duration ttl) {
	}

	/** Chunking and retrieval knobs. {@code minScore} is the cosine score below which a chunk counts as irrelevant. */
	public record Rag(int chunkSize, int chunkOverlap, int topK, int searchK, double minScore) {
	}

	public record Llm(String apiKey, String model, String effort, int maxTokens, Duration timeout) {
	}

	public record Demo(String password) {
	}

}
