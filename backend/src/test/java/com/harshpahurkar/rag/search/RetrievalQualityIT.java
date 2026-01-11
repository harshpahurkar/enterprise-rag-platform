package com.harshpahurkar.rag.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.harshpahurkar.rag.AppProperties;
import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.demo.DemoDataLoader;
import com.harshpahurkar.rag.document.IngestionService;
import com.harshpahurkar.rag.search.Retriever.RetrievedChunk;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the eval set against the demo docs (seeded by {@link DemoDataLoader}), prints a per-question table, and
 * measures the score gap that app.rag.min-score must sit in.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalQualityIT {

	static final List<String> ALL_ROLES = List.of("ADMIN", "HR", "FINANCE", "LEGAL", "ENGINEERING", "EMPLOYEE");

	record Question(String question, String expectedFile, List<String> roles) {
	}

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IngestionService ingestion;

	@Autowired
	PasswordEncoder encoder;

	@Autowired
	AppProperties props;

	@Autowired
	JsonMapper json;

	@Autowired
	Retriever retriever;

	int questions;

	int hits;

	double minOnTopic = Double.MAX_VALUE;

	double maxOffTopic = -Double.MAX_VALUE;

	@BeforeAll
	void seedAndEvaluate() throws Exception {
		jdbc.sql("DELETE FROM document").update();
		loader().run(null);

		Map<String, Long> docIdByFile = jdbc.sql("SELECT filename, id FROM document")
			.query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		var table = new StringBuilder(String.format(Locale.ROOT, "%n%-72s %-6s %s%n", "question", "rank", "top1"));
		for (String line : resource("eval/retrieval-questions.jsonl").lines().filter(l -> !l.isBlank()).toList()) {
			Question q = json.readValue(line, Question.class);
			List<RetrievedChunk> top = retriever.retrieve(q.question(), q.roles(), 5).chunks();
			long expected = docIdByFile.get(q.expectedFile());
			int rank = 0;
			for (int i = 0; i < top.size() && rank == 0; i++) {
				rank = top.get(i).documentId() == expected ? i + 1 : 0;
			}
			double top1 = top.isEmpty() ? 0 : top.get(0).score();
			questions++;
			hits += rank > 0 ? 1 : 0;
			minOnTopic = Math.min(minOnTopic, top1);
			table.append(String.format(Locale.ROOT, "%-72s %-6s %.3f%n", q.question(), rank > 0 ? rank : "miss", top1));
		}
		List<String> offTopic = new ArrayList<>();
		for (String q : resource("eval/offtopic-questions.txt").lines().filter(l -> !l.isBlank()).toList()) {
			double top1 = retriever.retrieve(q, ALL_ROLES, 1).chunks().get(0).score();
			maxOffTopic = Math.max(maxOffTopic, top1);
			offTopic.add(String.format(Locale.ROOT, "%-72s %-6s %.3f", q, "off", top1));
		}
		offTopic.forEach(l -> table.append(l).append('\n'));
		table.append(String.format(Locale.ROOT, "hits@5 %d/%d, min on-topic top1 %.3f, max off-topic top1 %.3f%n",
				hits, questions, minOnTopic, maxOffTopic));
		System.out.println(table);
	}

	@AfterAll
	void cleanUp() {
		jdbc.sql("DELETE FROM document").update();
	}

	DemoDataLoader loader() {
		return new DemoDataLoader(jdbc, ingestion, encoder, props, json);
	}

	static String resource(String path) throws IOException {
		return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void demoLoaderSeedsUsersAndDocsOnce() throws Exception {
		loader().run(null); // second run: documents already present, so nothing is re-ingested
		assertThat(jdbc.sql("SELECT count(*) FROM document WHERE uploaded_by = 'demo-loader'")
			.query(Integer.class)
			.single()).isEqualTo(6);
		assertThat(jdbc.sql("SELECT username FROM app_user").query(String.class).list())
			.contains("admin", "hr.manager", "finance.analyst", "legal.counsel", "engineer");
		String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE username = 'legal.counsel'")
			.query(String.class)
			.single();
		assertThat(encoder.matches(props.demo().password(), hash)).isTrue();
	}

	@Test
	void expectedDocumentIsInTopFive() {
		assertThat(questions).isEqualTo(18);
		assertThat(hits).as("questions whose expected doc is in the top 5").isGreaterThanOrEqualTo(16);
	}

	@Test
	void offTopicScoresStayBelowOnTopicScores() {
		assertThat(maxOffTopic).as("max off-topic top-1 %.3f must be below min on-topic top-1 %.3f", maxOffTopic,
				minOnTopic)
			.isLessThan(minOnTopic);
	}

}
