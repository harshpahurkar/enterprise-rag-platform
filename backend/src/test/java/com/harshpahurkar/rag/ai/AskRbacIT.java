package com.harshpahurkar.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.TestJwt;
import com.harshpahurkar.rag.document.IngestionService;
import com.jayway.jsonpath.JsonPath;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * /api/ask end to end with the real Retriever and real embeddings: what reaches the model is only what the
 * caller's roles allow, masked, and nothing at all when retrieval finds nothing relevant.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AskRbacIT {

	/** Records every request and always answers citing source [1]. */
	static class RecordingChatModel implements ChatModel {

		final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

		@Override
		public ChatResponse doChat(ChatRequest request) {
			requests.add(request);
			return ChatResponse.builder().aiMessage(AiMessage.from("From the sources [1].")).build();
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RecordingModelConfig {

		@Bean
		@Primary
		RecordingChatModel recordingChatModel() {
			return new RecordingChatModel();
		}

	}

	static final String SIN = "046-454-286";

	static final String EMAIL = "pat.lee@example.com";

	static final String PHONE = "416-555-0142";

	/** Under 1000 characters, so the salary bands and the contact details share one chunk. */
	static final String HR_TEXT = """
			# Salary bands 2026

			Senior Consultants earn between 98,000 and 121,000 dollars a year. Principal Consultants earn between
			125,000 and 150,000 dollars. Bands are reviewed every January by the compensation committee.

			HR contact for salary band questions: Pat Lee, HR business partner. Email %s or phone %s.
			Payroll record for Pat Lee: SIN %s.
			""".formatted(EMAIL, PHONE, SIN);

	static final String FINANCE_TEXT = """
			# Q3 revenue report

			Quarterly revenue reached 14.2 million dollars, up 9 percent on Q2. Accounts receivable over 60 days
			fell to 1.1 million. EBITDA margin was 18 percent. Capital expenditure was deferred to Q4.
			""";

	/** Text only the HR document holds; the questions below never contain it. */
	static final List<String> HR_ONLY = List.of("earn between 98,000 and 121,000", "Principal Consultants",
			"Pat Lee", "HR business partner");

	record Answer(boolean refused, List<Long> sourceDocIds) {
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IngestionService ingestion;

	@Autowired
	RecordingChatModel model;

	long hrDoc;

	long financeDoc;

	@BeforeAll
	void ingest() {
		hrDoc = ingest("Salary bands", "bands.md", HR_TEXT, "HR");
		financeDoc = ingest("Q3 revenue", "q3.md", FINANCE_TEXT, "FINANCE");
	}

	long ingest(String title, String filename, String text, String role) {
		return ingestion.ingest(title, filename, "text/markdown", new ByteArrayInputStream(text.getBytes()),
				List.of(role), "ask-rbac-test")
			.id();
	}

	@AfterAll
	void cleanUp() {
		jdbc.sql("DELETE FROM document WHERE id IN (:ids)").param("ids", List.of(hrDoc, financeDoc)).update();
	}

	@BeforeEach
	void reset() {
		model.requests.clear();
	}

	Answer ask(String question, RequestPostProcessor user) throws Exception {
		String body = mvc
			.perform(post("/api/ask").with(user)
				.contentType(MediaType.APPLICATION_JSON)
				.content(JsonMapper.builder().build().writeValueAsString(Map.of("question", question))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<Number> ids = JsonPath.read(body, "$.sources[*].documentId");
		return new Answer(JsonPath.read(body, "$.refused"), ids.stream().map(Number::longValue).toList());
	}

	/** Every message of every request the model received. */
	String prompts() {
		return model.requests.stream()
			.flatMap(r -> r.messages().stream())
			.map(AskRbacIT::text)
			.collect(Collectors.joining("\n"));
	}

	static String text(ChatMessage message) {
		return switch (message) {
			case SystemMessage s -> s.text();
			case UserMessage u -> u.singleText();
			case AiMessage a -> a.text();
			default -> message.toString();
		};
	}

	@Test
	void financeAskingAboutSalaryBandsGetsNoHrContent() throws Exception {
		Answer answer = ask("What are the salary bands for Senior Consultants?",
				TestJwt.as("fin", "FINANCE", "EMPLOYEE"));

		assertThat(answer.sourceDocIds()).doesNotContain(hrDoc).allMatch(id -> id == financeDoc);
		if (!answer.refused()) {
			assertThat(answer.sourceDocIds()).as("an answered question cites FINANCE sources").isNotEmpty();
		}
		assertThat(prompts()).doesNotContain(HR_ONLY);
	}

	@Test
	void hrContactDetailsAreMaskedBeforeTheModelSeesThem() throws Exception {
		Answer answer = ask("Who is the HR contact for salary band questions, and what is their email and phone?",
				TestJwt.as("hr", "HR", "EMPLOYEE"));

		assertThat(answer.refused()).isFalse();
		assertThat(answer.sourceDocIds()).contains(hrDoc);
		assertThat(model.requests).isNotEmpty();
		assertThat(prompts()).contains("[GOV_ID]", "[EMAIL]", "[PHONE]").doesNotContain(SIN, EMAIL, PHONE);
	}

	@Test
	void offTopicQuestionIsRefusedWithoutCallingTheModel() throws Exception {
		Answer answer = ask("What is the best sourdough starter?", TestJwt.as("hr", "HR", "EMPLOYEE"));

		assertThat(answer.refused()).isTrue();
		assertThat(model.requests).isEmpty();
	}

}
