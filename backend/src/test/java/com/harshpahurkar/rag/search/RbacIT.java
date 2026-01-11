package com.harshpahurkar.rag.search;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.TestJwt;
import com.harshpahurkar.rag.document.IngestionService;

/** Role filtering is enforced in SQL, so even a k=50 candidate pool never holds a forbidden chunk. */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RbacIT {

	static final String HR_TEXT = """
			# Salary bands 2026

			Senior Consultants earn between 98,000 and 121,000 dollars a year. Principal Consultants earn between
			125,000 and 150,000 dollars. Bands are reviewed every January by the compensation committee.

			Parental leave tops salary up to 85 percent for 17 weeks. Performance calibration happens in November.
			""";

	static final String FINANCE_TEXT = """
			# Q3 revenue report

			Quarterly revenue reached 14.2 million dollars, up 9 percent on Q2. Accounts receivable over 60 days
			fell to 1.1 million. EBITDA margin was 18 percent.

			The largest client by billing was Corvane Freight. Capital expenditure was deferred to Q4.
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IngestionService ingestion;

	long hrDoc;

	long financeDoc;

	@BeforeAll
	void ingest() {
		jdbc.sql("DELETE FROM document").update();
		hrDoc = ingestion
			.ingest("Salary bands", "bands.md", "text/markdown", new ByteArrayInputStream(HR_TEXT.getBytes()),
					List.of("HR"), "rbac-test")
			.id();
		financeDoc = ingestion
			.ingest("Q3 revenue", "q3.md", "text/markdown", new ByteArrayInputStream(FINANCE_TEXT.getBytes()),
					List.of("FINANCE"), "rbac-test")
			.id();
	}

	@AfterAll
	void cleanUp() {
		jdbc.sql("DELETE FROM document").update();
	}

	ResultActions search(String query, int k, RequestPostProcessor user) throws Exception {
		return mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
			.content("{\"query\":\"" + query + "\",\"k\":" + k + "}")
			.with(user)).andExpect(status().isOk()).andExpect(jsonPath("$.retrievalMs").isNumber());
	}

	@Test
	void financeUserNeverGetsHrChunks() throws Exception {
		search("What is the salary band for a Senior Consultant?", 50, TestJwt.as("fin", "FINANCE", "EMPLOYEE"))
			.andExpect(jsonPath("$.results").isNotEmpty())
			.andExpect(jsonPath("$.results[?(@.documentId == %d)]", hrDoc).isEmpty())
			.andExpect(jsonPath("$.results[?(@.documentId != %d)]", financeDoc).isEmpty());
	}

	@Test
	void hrUserGetsHrChunks() throws Exception {
		search("What is the salary band for a Senior Consultant?", 50, TestJwt.as("hr", "HR", "EMPLOYEE"))
			.andExpect(jsonPath("$.results[0].documentId").value(hrDoc))
			.andExpect(jsonPath("$.results[0].title").value("Salary bands"))
			.andExpect(jsonPath("$.results[0].content").isString())
			.andExpect(jsonPath("$.results[0].score").isNumber())
			.andExpect(jsonPath("$.results[?(@.documentId == %d)]", financeDoc).isEmpty());
	}

	@Test
	void userInNeitherListGetsEmptyResult() throws Exception {
		search("What is the salary band for a Senior Consultant?", 50, TestJwt.as("legal", "LEGAL", "EMPLOYEE"))
			.andExpect(jsonPath("$.results").isEmpty());
	}

	@Test
	void documentListIsFilteredTheSameWay() throws Exception {
		mvc.perform(get("/api/documents").with(TestJwt.as("fin", "FINANCE", "EMPLOYEE")))
			.andExpect(jsonPath("$[*].id", contains((int) financeDoc)));
		mvc.perform(get("/api/documents").with(TestJwt.as("hr", "HR", "EMPLOYEE")))
			.andExpect(jsonPath("$[*].id", contains((int) hrDoc)));
		mvc.perform(get("/api/documents").with(TestJwt.as("legal", "LEGAL", "EMPLOYEE")))
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void searchRejectsBadInput() throws Exception {
		var hr = TestJwt.as("hr", "HR");
		mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"\"}").with(hr))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
			.content("{\"query\":\"" + "x".repeat(1001) + "\"}")
			.with(hr)).andExpect(status().isBadRequest());
		mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
			.content("{\"query\":\"bands\",\"k\":51}")
			.with(hr)).andExpect(status().isBadRequest());
		mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
			.content("{\"query\":\"bands\",\"k\":0}")
			.with(hr)).andExpect(status().isBadRequest());
	}

}
