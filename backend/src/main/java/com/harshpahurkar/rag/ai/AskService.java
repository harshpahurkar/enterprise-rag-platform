package com.harshpahurkar.rag.ai;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import com.harshpahurkar.rag.AppProperties;
import com.harshpahurkar.rag.search.Retriever;
import com.harshpahurkar.rag.search.Retriever.Retrieval;
import com.harshpahurkar.rag.search.Retriever.RetrievedChunk;

import dev.langchain4j.guardrail.InputGuardrailException;

/** Check for injection, retrieve with the caller's roles, keep the relevant chunks, number them, ask Claude. */
@Service
public class AskService {

	public record Source(int n, long chunkId, long documentId, String title, int chunkIndex, String content,
			double score) {
	}

	public record AskResponse(String answer, boolean refused, List<Source> sources, long retrievalMs,
			long generationMs) {
	}

	private final Retriever retriever;

	private final Assistant assistant;

	private final AppProperties.Rag rag;

	public AskService(Retriever retriever, Assistant assistant, AppProperties props) {
		this.retriever = retriever;
		this.assistant = assistant;
		this.rag = props.rag();
	}

	public AskResponse ask(String question, List<String> userRoles) {
		// Before retrieval, so an injection attempt is blocked (422) even when it matches no document.
		if (PromptInjectionGuardrail.looksLikeInjection(question)) {
			throw new InputGuardrailException(PromptInjectionGuardrail.BLOCKED);
		}
		Retrieval retrieval = retriever.retrieve(question, userRoles, rag.topK());
		// Only relevant chunks go on: less client text sent to the third-party API, and less noise in the answer.
		List<RetrievedChunk> chunks = retrieval.chunks().stream().filter(c -> c.score() >= rag.minScore()).toList();
		// Score gate: when nothing is relevant enough, skip the LLM. No cost, and nothing to make an answer up from.
		if (chunks.isEmpty()) {
			return new AskResponse(Assistant.REFUSAL, true, List.of(), retrieval.retrievalMs(), 0);
		}

		List<Source> sources = IntStream.range(0, chunks.size()).mapToObj(i -> {
			RetrievedChunk c = chunks.get(i);
			return new Source(i + 1, c.chunkId(), c.documentId(), c.title(), c.chunkIndex(), c.content(), c.score());
		}).toList();
		String context = sources.stream()
			.map(s -> "[" + s.n() + "] " + escape(s.title()) + " (part " + (s.chunkIndex() + 1) + ")\n"
					+ escape(s.content()))
			.collect(Collectors.joining("\n\n"));

		String memoryId = UUID.randomUUID().toString();
		long start = System.nanoTime();
		String answer;
		try {
			answer = assistant.answer(memoryId, question, context, sources.size());
		}
		finally {
			assistant.evictChatMemory(memoryId);
		}
		long generationMs = (System.nanoTime() - start) / 1_000_000;

		// A declined request can come back blank; show it as the refusal.
		boolean refused = answer == null || answer.isBlank() || answer.strip().equals(Assistant.REFUSAL);
		return new AskResponse(refused ? Assistant.REFUSAL : answer, refused, sources, retrieval.retrievalMs(),
				generationMs);
	}

	/** Document text can't close the {@code <sources>} block and write its own instructions after it. */
	private static String escape(String text) {
		return text.replace("<", "&lt;").replace(">", "&gt;");
	}

}
