package com.harshpahurkar.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.TestJwt;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class IngestionIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	/** About 4,000 chars: 8 paragraphs of 5 unique, numbered sentences, so overlap is visible and unambiguous. */
	static String handbook() {
		var sb = new StringBuilder("# Travel and expense handbook\n\n");
		for (int p = 1; p <= 8; p++) {
			for (int s = 1; s <= 5; s++) {
				sb.append("Rule ").append(p).append('.').append(s)
					.append(" says receipts for client travel must be filed within thirty days of the trip ending. ");
			}
			sb.append("\n\n");
		}
		return sb.toString();
	}

	@Test
	void uploadChunksEmbedsListsAndDeletes() throws Exception {
		var file = new MockMultipartFile("file", "travel-handbook.md", "text/markdown", handbook().getBytes());
		String body = mvc
			.perform(multipart("/api/documents").file(file).param("allowedRoles", "HR", "EMPLOYEE")
				.with(TestJwt.as("admin", "ADMIN")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("travel-handbook"))
			.andExpect(jsonPath("$.filename").value("travel-handbook.md"))
			.andExpect(jsonPath("$.uploadedBy").value("admin"))
			.andExpect(jsonPath("$.chunkCount", greaterThan(1)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long id = JsonMapper.builder().build().readTree(body).get("id").asLong();

		List<String> chunks = jdbc.sql("SELECT content FROM chunk WHERE document_id = ? ORDER BY chunk_index")
			.param(id)
			.query(String.class)
			.list();
		assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(1000));
		for (int i = 1; i < chunks.size(); i++) {
			// The next chunk opens with the tail of the previous one.
			assertThat(chunks.get(i - 1)).contains(chunks.get(i).substring(0, 40));
		}
		assertThat(jdbc.sql("SELECT DISTINCT vector_dims(embedding) FROM chunk WHERE document_id = ?")
			.param(id)
			.query(Integer.class)
			.list()).containsExactly(384);

		mvc.perform(get("/api/documents").with(TestJwt.as("hr", "HR")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == %d)]", id).isNotEmpty());
		mvc.perform(get("/api/documents").with(TestJwt.as("fin", "FINANCE")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == %d)]", id).isEmpty());

		mvc.perform(delete("/api/documents/{id}", id).with(TestJwt.as("admin", "ADMIN")))
			.andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT count(*) FROM chunk WHERE document_id = ?").param(id).query(Long.class).single())
			.isZero();
		mvc.perform(delete("/api/documents/{id}", id).with(TestJwt.as("admin", "ADMIN")))
			.andExpect(status().isNotFound());
	}

	@Test
	void unknownRoleIs400() throws Exception {
		var file = new MockMultipartFile("file", "x.md", "text/markdown", "# Hello\n\nSome text.".getBytes());
		mvc.perform(multipart("/api/documents").file(file).param("allowedRoles", "HR", "JANITOR")
			.with(TestJwt.as("admin", "ADMIN")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail", containsString("JANITOR")));
	}

	@Test
	void missingRolesIs400() throws Exception {
		var file = new MockMultipartFile("file", "x.md", "text/markdown", "# Hello\n\nSome text.".getBytes());
		mvc.perform(multipart("/api/documents").file(file).with(TestJwt.as("admin", "ADMIN")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail", containsString("allowedRoles")));
	}

	@Test
	void emptyFileIs400() throws Exception {
		var file = new MockMultipartFile("file", "empty.md", "text/markdown", new byte[0]);
		mvc.perform(multipart("/api/documents").file(file).param("allowedRoles", "HR")
			.with(TestJwt.as("admin", "ADMIN")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail", containsString("no extractable text")));
	}

}
