package com.harshpahurkar.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import com.harshpahurkar.rag.IntegrationTest;

/**
 * Upload and delete are checked at the controller method, not only by the URL rules, so a second route to the same
 * method (or a URL rule that drifts) can't skip the ADMIN check. These call the controller bean directly, with no
 * filter chain in front of it.
 */
@IntegrationTest
class DocumentAccessIT {

	@Autowired
	DocumentController documents;

	@Autowired
	IngestionService ingestion;

	@Autowired
	JdbcClient jdbc;

	long hrDoc;

	@BeforeEach
	void document() {
		byte[] text = "# Access memo\n\nThis memo exists only for DocumentAccessIT.".getBytes();
		hrDoc = ingestion.ingest("Access memo", "memo.md", "text/markdown", new ByteArrayInputStream(text),
				List.of("HR"), "document-access-it")
			.id();
	}

	@AfterEach
	void cleanUp() {
		jdbc.sql("DELETE FROM document WHERE uploaded_by IN ('document-access-it', 'hr.user')").update();
	}

	boolean exists(long id) {
		return jdbc.sql("SELECT count(*) FROM document WHERE id = ?").param(id).query(Long.class).single() > 0;
	}

	@Test
	@WithMockUser(username = "hr.user", roles = "HR")
	void deleteMethodDeniesNonAdmin() {
		var auth = SecurityContextHolder.getContext().getAuthentication();
		assertThatThrownBy(() -> documents.delete(hrDoc, auth)).isInstanceOf(AccessDeniedException.class);
		assertThat(exists(hrDoc)).isTrue();
	}

	@Test
	@WithMockUser(username = "hr.user", roles = "HR")
	void uploadMethodDeniesNonAdmin() {
		var file = new MockMultipartFile("file", "x.md", "text/markdown", "# Hi\n\nA note.".getBytes());
		var auth = SecurityContextHolder.getContext().getAuthentication();
		assertThatThrownBy(() -> documents.upload(file, "Denied upload", List.of("HR"), auth))
			.isInstanceOf(AccessDeniedException.class);
		assertThat(jdbc.sql("SELECT count(*) FROM document WHERE title = 'Denied upload'").query(Long.class).single())
			.isZero();
	}

}
