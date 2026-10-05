package com.harshpahurkar.rag.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.harshpahurkar.rag.ai.AskService.AskResponse;
import com.harshpahurkar.rag.security.Roles;

import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
class AskController {

	private static final Logger log = LoggerFactory.getLogger(AskController.class);

	record AskRequest(@NotBlank @Size(max = 1000) String question) {
	}

	private final AskService askService;

	AskController(AskService askService) {
		this.askService = askService;
	}

	@PostMapping("/ask")
	AskResponse ask(@Valid @RequestBody AskRequest req, Authentication authentication) {
		return askService.ask(req.question(), Roles.of(authentication));
	}

	/**
	 * Fixed text: the exception's own message names guardrail classes. Only the injection check can fail input,
	 * in AskService or in the AI Service. A missing API key is a ResponseStatusException (503) from AiConfig.
	 */
	@ExceptionHandler(InputGuardrailException.class)
	ProblemDetail blocked(InputGuardrailException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
				"The question was blocked because it looks like an attempt to change the assistant's instructions. "
						+ "Please rephrase it.");
	}

	@ExceptionHandler(OutputGuardrailException.class)
	ProblemDetail ungrounded(OutputGuardrailException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
				"The answer could not be grounded in your documents");
	}

	/** Provider and HTTP failures. Details go to the log, never to the caller. */
	@ExceptionHandler(LangChain4jException.class)
	ProblemDetail upstream(LangChain4jException e) {
		log.warn("LLM call failed", e);
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
				"The language model service failed. Please try again.");
	}

}
