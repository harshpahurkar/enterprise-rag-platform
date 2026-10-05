package com.harshpahurkar.rag.ai;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.TestJwt;
import com.harshpahurkar.rag.search.Retriever;
import com.harshpahurkar.rag.search.Retriever.Retrieval;
import com.harshpahurkar.rag.search.Retriever.RetrievedChunk;

/** One real Claude call through controller, guardrails and prompts. Costs money, so it runs only with a key. */
@IntegrationTest
@TestPropertySource(properties = "app.llm.api-key=${ANTHROPIC_API_KEY:}")
class ClaudeLiveIT {

	@BeforeAll
	static void requireApiKey() {
		String key = System.getenv("ANTHROPIC_API_KEY");
		Assumptions.assumeTrue(key != null && !key.isBlank(), "ANTHROPIC_API_KEY is not set");
	}

	@Autowired
	MockMvc mvc;

	@MockitoBean
	Retriever retriever;

	@Test
	void claudeAnswersFromTheSuppliedChunkWithACitation() throws Exception {
		var chunk = new RetrievedChunk(1, 1, "Employee Handbook", 0,
				"New employees receive 20 days of paid vacation per year, rising to 25 days after five years.", 0.9);
		when(retriever.retrieve(anyString(), anyList(), anyInt())).thenReturn(new Retrieval(List.of(chunk), 3));

		mvc.perform(post("/api/ask").with(TestJwt.as("eng", "EMPLOYEE"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"question\":\"How many vacation days does a new employee get?\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.refused").value(false))
			.andExpect(jsonPath("$.answer", containsString("[1]")))
			.andExpect(jsonPath("$.answer", containsString("20")));
	}

}
