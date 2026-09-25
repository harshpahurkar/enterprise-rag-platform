package com.harshpahurkar.rag.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.guardrail.InputGuardrails;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import dev.langchain4j.service.memory.ChatMemoryAccess;

/**
 * Claude behind a LangChain4j AI Service. Input guardrails run in order: the injection check on the raw
 * question, then PII masking of the whole rendered message. The output guardrail checks citations.
 * <p>
 * Each call needs its own {@code memoryId}, and the caller evicts it afterwards. LangChain4j builds a re-prompt
 * from chat memory only, so without one the re-prompt would reach the model with no system prompt and no
 * sources. The memory stores the masked message, so the re-prompt stays masked too.
 */
public interface Assistant extends ChatMemoryAccess {

	String REFUSAL = "I couldn't find that in the documents you have access to.";

	@SystemMessage(fromResource = "/prompts/system.txt")
	@UserMessage(fromResource = "/prompts/answer.txt")
	@InputGuardrails({ PromptInjectionGuardrail.class, PiiMaskingGuardrail.class })
	// LangChain4j counts maxRetries as total attempts: 2 = the first answer plus at most one re-prompt.
	@OutputGuardrails(value = CitationGuardrail.class, maxRetries = 2)
	String answer(@MemoryId String memoryId, @V("question") String question, @V("context") String context,
			@V("sourceCount") int sourceCount);

}
