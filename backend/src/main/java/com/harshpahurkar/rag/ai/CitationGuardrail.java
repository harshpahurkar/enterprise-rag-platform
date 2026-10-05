package com.harshpahurkar.rag.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;

/**
 * Accepts an answer that cites sources as [n] or [n, m, ...], every number a real source, or that is exactly the
 * refusal sentence. Anything else is re-prompted (bounded by maxRetries on {@link Assistant}). A blank answer
 * passes too: it is what a declined request leaves, re-asking won't fix it, and AskService turns it into the
 * refusal.
 * <p>
 * The source count comes from the {@code sourceCount} template variable, never from counting "[n]" headers in
 * the context: a chunk can contain text that looks like a header.
 */
public class CitationGuardrail implements OutputGuardrail {

	/** [1], [1, 2] or [1,2,3]. */
	private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)\\]");

	@Override
	public OutputGuardrailResult validate(OutputGuardrailRequest request) {
		String answer = Objects.toString(request.responseFromLLM().aiMessage().text(), "").trim();
		if (answer.isEmpty() || answer.equals(Assistant.REFUSAL)) {
			return success();
		}
		int sources = request.requestParams().variables().get("sourceCount") instanceof Number n ? n.intValue() : 0;
		List<Integer> cited = CITATION.matcher(answer)
			.results()
			.flatMap(m -> Arrays.stream(m.group(1).split(",")))
			.map(n -> Integer.parseInt(n.strip()))
			.toList();
		if (!cited.isEmpty() && cited.stream().allMatch(n -> n >= 1 && n <= sources)) {
			return success();
		}
		return reprompt("Answer must cite only sources 1 to " + sources,
				"Cite the sources you used as [n], using only numbers 1 to " + sources
						+ ". If the sources don't answer the question, reply with exactly: " + Assistant.REFUSAL);
	}

}
