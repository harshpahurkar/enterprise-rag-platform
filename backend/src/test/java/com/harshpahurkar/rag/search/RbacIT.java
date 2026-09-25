package com.harshpahurkar.rag.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.hamcrest.Matchers;
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
import com.jayway.jsonpath.JsonPath;

/**
 * Role filtering is enforced in SQL, so even a k=50 candidate pool never holds a forbidden chunk. Assertions look
 * only at this class's own documents, so rows other classes leave in the shared database don't change the outcome.
 */
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

	static final String ADMIN_TEXT = """
			# Board succession plan

			The board has named an interim chief executive should the managing partner step down. The succession
			shortlist for chief executive is reviewed every spring by the board chair and two outside directors.
			""";

	static final String SALARY_QUESTION = "What is the salary band for a Senior Consultant?";

	static final String SUCCESSION_QUESTION = "Who is on the succession shortlist for chief executive?";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IngestionService ingestion;

	long hrDoc;

	long financeDoc;

	long adminDoc;

	@BeforeAll
	void ingest() {
		hrDoc = ingest("Salary bands", "bands.md", HR_TEXT, "HR");
		financeDoc = ingest("Q3 revenue", "q3.md", FINANCE_TEXT, "FINANCE");
		adminDoc = ingest("Succession plan", "succession.md", ADMIN_TEXT, "ADMIN");
	}

	long ingest(String title, String filename, String text, String role) {
		return ingestion.ingest(title, filename, "text/markdown", new ByteArrayInputStream(text.getBytes()),
				List.of(role), "rbac-test")
			.id();
	}

	@AfterAll
	void cleanUp() {
		jdbc.sql("DELETE FROM document WHERE id IN (:ids)").param("ids", List.of(hrDoc, financeDoc, adminDoc)).update();
	}

	ResultActions search(String query, int k, RequestPostProcessor user) throws Exception {
		return mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
			.content("{\"query\":\"" + query + "\",\"k\":" + k + "}")
			.with(user)).andExpect(status().isOk()).andExpect(jsonPath("$.retrievalMs").isNumber());
	}

	List<Long> searchedDocIds(String query, RequestPostProcessor user) throws Exception {
		return docIds(search(query, 50, user), "$.results[*].documentId");
	}

	List<Long> listedDocIds(RequestPostProcessor user) throws Exception {
		return docIds(mvc.perform(get("/api/documents").with(user)).andExpect(status().isOk()), "$[*].id");
	}

	static List<Long> docIds(ResultActions result, String path) throws Exception {
		List<Number> ids = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
		return ids.stream().map(Number::longValue).toList();
	}

	@Test
	void financeUserNeverGetsHrChunks() throws Exception {
		assertThat(searchedDocIds(SALARY_QUESTION, TestJwt.as("fin", "FINANCE", "EMPLOYEE"))).contains(financeDoc)
			.doesNotContain(hrDoc, adminDoc);
	}

	@Test
	void hrUserGetsHrChunks() throws Exception {
		search(SALARY_QUESTION, 50, TestJwt.as("hr", "HR", "EMPLOYEE"))
			.andExpect(jsonPath("$.results[?(@.documentId == %d)].title", hrDoc)
				.value(Matchers.hasItem("Salary bands")))
			.andExpect(jsonPath("$.results[?(@.documentId == %d)].content", hrDoc).value(Matchers.hasItem(
					Matchers.containsString("Senior Consultants earn between"))))
			.andExpect(jsonPath("$.results[?(@.documentId == %d)].score", hrDoc).isNotEmpty())
			.andExpect(jsonPath("$.results[?(@.documentId == %d)]", financeDoc).isEmpty())
			.andExpect(jsonPath("$.results[?(@.documentId == %d)]", adminDoc).isEmpty());
	}

	@Test
	void userInNeitherListGetsNoneOfTheseDocuments() throws Exception {
		assertThat(searchedDocIds(SALARY_QUESTION, TestJwt.as("legal", "LEGAL", "EMPLOYEE")))
			.doesNotContain(hrDoc, financeDoc, adminDoc);
	}

	@Test
	void adminOnlyDocumentIsHiddenFromOtherRolesAndVisibleToAdmin() throws Exception {
		var hr = TestJwt.as("hr", "HR", "EMPLOYEE");
		assertThat(searchedDocIds(SUCCESSION_QUESTION, hr)).doesNotContain(adminDoc);
		assertThat(listedDocIds(hr)).doesNotContain(adminDoc);

		var admin = TestJwt.as("admin", "ADMIN");
		assertThat(searchedDocIds(SUCCESSION_QUESTION, admin)).contains(adminDoc).doesNotContain(hrDoc, financeDoc);
		assertThat(listedDocIds(admin)).contains(adminDoc).doesNotContain(hrDoc, financeDoc);
	}

	@Test
	void documentListIsFilteredTheSameWay() throws Exception {
		assertThat(listedDocIds(TestJwt.as("fin", "FINANCE", "EMPLOYEE"))).contains(financeDoc)
			.doesNotContain(hrDoc, adminDoc);
		assertThat(listedDocIds(TestJwt.as("hr", "HR", "EMPLOYEE"))).contains(hrDoc)
			.doesNotContain(financeDoc, adminDoc);
		assertThat(listedDocIds(TestJwt.as("legal", "LEGAL", "EMPLOYEE"))).doesNotContain(hrDoc, financeDoc,
				adminDoc);
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

	/**
	 * Role-filtered HNSW search depends on two per-connection settings from application.yml: without the
	 * iterative scan a selective filter returns fewer than k rows, and a generic plan skips the index entirely.
	 */
	@Test
	void everyPooledConnectionKeepsFilteredSearchOnTheIndex() {
		assertThat(jdbc.sql("SHOW hnsw.iterative_scan").query(String.class).single()).isEqualTo("strict_order");
		assertThat(jdbc.sql("SHOW plan_cache_mode").query(String.class).single()).isEqualTo("force_custom_plan");
	}

}
