package com.harshpahurkar.rag.ai;

import java.util.regex.Pattern;

import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;

/**
 * Blocks questions that try to override the assistant's instructions. Reads only the raw {@code question}
 * template variable, never the retrieved context: the system prompt already treats sources as untrusted data,
 * and a document that happens to quote these phrases must not block legitimate questions.
 */
// ponytail: regex heuristic, beaten by paraphrase or another language; swap in a prompt-injection classifier
// (a small fine-tuned model or a moderation endpoint) when false negatives start to matter.
public class PromptInjectionGuardrail implements InputGuardrail {

	private static final Pattern INJECTION = Pattern.compile(String.join("|",
			"\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(previous|prior|above|earlier|preceding)\\s+(instructions|prompts?|rules|directions)\\b",
			"\\b(reveal|print|show|repeat|output|display|leak)\\b.{0,40}\\b(system|hidden|initial)\\s+(prompt|instructions|message)\\b",
			"\\byou\\s+are\\s+now\\b", "\\bdeveloper\\s+mode\\b", "\\bjailbreak", "</?\\s*sources\\s*>"),
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	@Override
	public InputGuardrailResult validate(InputGuardrailRequest request) {
		Object question = request.requestParams().variables().get("question");
		if (question != null && INJECTION.matcher(question.toString()).find()) {
			return fatal("The question looks like an attempt to change the assistant's instructions.");
		}
		return success();
	}

}
