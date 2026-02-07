package com.harshpahurkar.rag.ai;

import java.util.Objects;
import java.util.regex.Pattern;

import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;

/**
 * Accepts an answer that cites at least one real source as [n], or that is exactly the refusal sentence.
 * Anything else is re-prompted (bounded by maxRetries on {@link Assistant}). A blank answer passes too:
 * it is what a declined request leaves, re-asking won't fix it, and AskService turns it into the refusal.
 */
public class CitationGuardrail implements OutputGuardrail {

	/** The source headers AskService writes: "[n] Title (part k)". */
	private static final Pattern SOURCE_HEADER = Pattern.compile("^\\[\\d+\\] .* \\(part \\d+\\)$", Pattern.MULTILINE);

	private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})\\]");

	@Override
	public OutputGuardrailResult validate(OutputGuardrailRequest request) {
		String answer = Objects.toString(request.responseFromLLM().aiMessage().text(), "").trim();
		if (answer.isEmpty() || answer.equals(Assistant.REFUSAL)) {
			return success();
		}
		Object context = request.requestParams().variables().get("context");
		long sources = SOURCE_HEADER.matcher(Objects.toString(context, "")).results().count();
		boolean cited = CITATION.matcher(answer)
			.results()
			.mapToInt(m -> Integer.parseInt(m.group(1)))
			.anyMatch(n -> n >= 1 && n <= sources);
		if (cited) {
			return success();
		}
		return reprompt("Answer cites no source between 1 and " + sources,
				"Cite the sources you used as [n], using only numbers 1 to " + sources
						+ ". If the sources don't answer the question, reply with exactly: " + Assistant.REFUSAL);
	}

}
