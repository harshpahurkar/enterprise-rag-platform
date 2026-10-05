package com.harshpahurkar.rag.ai;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.harshpahurkar.rag.AppProperties;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.service.AiServices;

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

	/**
	 * Claude, with no temperature/top_p/top_k (Opus 5.5 rejects them) and no thinking config (it can't be
	 * turned off). Effort goes in output_config. The fallback beta lets the API retry a request a safety
	 * classifier declined on another model instead of failing it. Without a key the app still starts (search
	 * needs no LLM) and only /api/ask fails, as a 503.
	 */
	@Bean
	ChatModel chatModel(AppProperties props) {
		AppProperties.Llm llm = props.llm();
		if (llm.apiKey() == null || llm.apiKey().isBlank()) {
			return new ChatModel() {
				@Override
				public ChatResponse doChat(ChatRequest request) {
					throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
				}
			};
		}
		return AnthropicChatModel.builder()
			.apiKey(llm.apiKey())
			.modelName(llm.model())
			.maxTokens(llm.maxTokens())
			.timeout(llm.timeout())
			.beta("server-side-fallback-2026-07-01")
			.customParameters(Map.of("output_config", Map.of("effort", llm.effort()), "fallbacks", "default"))
			.build();
	}

	/** One chat memory per call (see {@link Assistant}); AskService evicts it when the call ends. */
	@Bean
	Assistant assistant(ChatModel chatModel) {
		return AiServices.builder(Assistant.class)
			.chatModel(chatModel)
			.chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
			.build();
	}

}
