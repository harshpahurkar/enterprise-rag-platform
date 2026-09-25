package com.harshpahurkar.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.TestJwt;
import com.jayway.jsonpath.JsonPath;

/** Binary formats go through Tika: an uploaded PDF or DOCX ends up as searchable chunk text. */
@IntegrationTest
class DocumentFormatsIT {

	static final String PDF_PHRASE = "Client laptops are encrypted before they leave the office.";

	static final String DOCX_PHRASE = "Timesheets are due every Friday by five in the afternoon.";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	final List<Long> created = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		if (!created.isEmpty()) {
			jdbc.sql("DELETE FROM document WHERE id IN (:ids)").param("ids", created).update();
			created.clear();
		}
	}

	static byte[] pdf(String text) throws IOException {
		try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
			var page = new PDPage();
			doc.addPage(page);
			try (var content = new PDPageContentStream(doc, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(72, 720);
				content.showText(text);
				content.endText();
			}
			doc.save(out);
			return out.toByteArray();
		}
	}

	static byte[] docx(String text) throws IOException {
		try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
			doc.createParagraph().createRun().setText(text);
			doc.write(out);
			return out.toByteArray();
		}
	}

	void uploadAndFindPhrase(MockMultipartFile file, String phrase) throws Exception {
		String body = mvc
			.perform(multipart("/api/documents").file(file).param("allowedRoles", "EMPLOYEE")
				.with(TestJwt.as("admin", "ADMIN")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.chunkCount", greaterThan(0)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long id = JsonPath.<Number>read(body, "$.id").longValue();
		created.add(id);

		assertThat(jdbc.sql("SELECT content FROM chunk WHERE document_id = ?").param(id).query(String.class).list())
			.anySatisfy(chunk -> assertThat(chunk).contains(phrase));
	}

	@Test
	void pdfUploadIsParsedIntoChunks() throws Exception {
		uploadAndFindPhrase(new MockMultipartFile("file", "laptops.pdf", "application/pdf", pdf(PDF_PHRASE)),
				PDF_PHRASE);
	}

	@Test
	void docxUploadIsParsedIntoChunks() throws Exception {
		uploadAndFindPhrase(new MockMultipartFile("file", "timesheets.docx",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx(DOCX_PHRASE)),
				DOCX_PHRASE);
	}

}
