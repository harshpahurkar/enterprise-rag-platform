package com.harshpahurkar.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

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

		// Delete is scoped by the caller's roles too, so the admin holds the document's roles, as the demo admin does.
		mvc.perform(delete("/api/documents/{id}", id).with(TestJwt.as("admin", "ADMIN", "HR", "EMPLOYEE")))
			.andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT count(*) FROM chunk WHERE document_id = ?").param(id).query(Long.class).single())
			.isZero();
		mvc.perform(delete("/api/documents/{id}", id).with(TestJwt.as("admin", "ADMIN", "HR", "EMPLOYEE")))
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

	private long count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	/** Uploads as ADMIN, expects 400, and checks that no document or chunk row was written. */
	private ResultActions rejectedWithoutRows(MockMultipartHttpServletRequestBuilder upload) throws Exception {
		long documents = count("document");
		long chunks = count("chunk");
		ResultActions result = mvc.perform(upload.param("allowedRoles", "HR").with(TestJwt.as("admin", "ADMIN")))
			.andExpect(status().isBadRequest());
		assertThat(count("document")).isEqualTo(documents);
		assertThat(count("chunk")).isEqualTo(chunks);
		return result;
	}

	@Test
	void corruptPdfIs400() throws Exception {
		byte[] truncated = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 ".getBytes();
		var file = new MockMultipartFile("file", "contract.pdf", "application/pdf", truncated);
		rejectedWithoutRows(multipart("/api/documents").file(file))
			.andExpect(jsonPath("$.detail", containsString("could not be read")));
	}

	@Test
	void pngRenamedToMarkdownIs400() throws Exception {
		var png = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", png);
		var file = new MockMultipartFile("file", "notes.md", "text/markdown", png.toByteArray());
		// Tika detects the PNG by its magic bytes and finds no text in it.
		rejectedWithoutRows(multipart("/api/documents").file(file))
			.andExpect(jsonPath("$.detail", containsString("no extractable text")));
	}

	@Test
	void textOverFiveMillionCharactersIs400() throws Exception {
		var file = new MockMultipartFile("file", "huge.txt", "text/plain", "word ".repeat(1_000_001).getBytes());
		rejectedWithoutRows(multipart("/api/documents").file(file))
			.andExpect(jsonPath("$.detail", containsString("too large")))
			.andExpect(jsonPath("$.detail", containsString("characters")));
	}

	@Test
	void moreThan2000ChunksIs400() throws Exception {
		// About 600 characters per paragraph, so two never share a 1000-character chunk: 2001 paragraphs, 2001+ chunks.
		var sb = new StringBuilder();
		for (int p = 0; p <= 2000; p++) {
			sb.append(("Paragraph " + p + " says receipts are filed within thirty days. ").repeat(10)).append("\n\n");
		}
		var file = new MockMultipartFile("file", "long.md", "text/markdown", sb.toString().getBytes());
		rejectedWithoutRows(multipart("/api/documents").file(file))
			.andExpect(jsonPath("$.detail", containsString("too large")))
			.andExpect(jsonPath("$.detail", containsString("chunks")));
	}

	@Test
	void titleOver200CharactersIs400() throws Exception {
		var file = new MockMultipartFile("file", "x.md", "text/markdown", "# Hello\n\nSome text.".getBytes());
		rejectedWithoutRows(multipart("/api/documents").file(file).param("title", "t".repeat(201)));
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
