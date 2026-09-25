package com.harshpahurkar.rag.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.harshpahurkar.rag.AppProperties;
import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.demo.DemoDataLoader;
import com.harshpahurkar.rag.document.IngestionService;
import com.harshpahurkar.rag.search.Retriever.RetrievedChunk;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the eval set against the demo docs (ingested from the {@link DemoDataLoader} manifest), prints a
 * per-question table, and checks that app.rag.min-score sits in the gap between off-topic and on-topic scores.
 * Only this class's own documents are ranked and cleaned up, so other rows in the shared database don't matter.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalQualityIT {

	static final List<String> ALL_ROLES = List.of("ADMIN", "HR", "FINANCE", "LEGAL", "ENGINEERING", "EMPLOYEE");

	record Question(String question, String expectedFile, List<String> roles) {
	}

	record ManifestEntry(String file, String title, List<String> allowedRoles) {
	}

	record Eval(int questions, List<String> misses, double minOnTopic, double maxOffTopic) {
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

	final Map<String, Long> docIdByFile = new HashMap<>();

	ManifestEntry[] manifest;

	Eval evaluation;

	@BeforeAll
	void ingestDemoDocs() throws IOException {
		manifest = json.readValue(resource("demo-docs/manifest.json"), ManifestEntry[].class);
		for (ManifestEntry entry : manifest) {
			try (InputStream in = new ClassPathResource("demo-docs/" + entry.file()).getInputStream()) {
				long id = ingestion.ingest(entry.title(), entry.file(), "text/markdown", in, entry.allowedRoles(),
						"retrieval-quality-it").id();
				docIdByFile.put(entry.file(), id);
			}
		}
	}

	@AfterAll
	void cleanUp() {
		if (!docIdByFile.isEmpty()) {
			jdbc.sql("DELETE FROM document WHERE id IN (:ids)").param("ids", docIdByFile.values()).update();
		}
	}

	DemoDataLoader loader() {
		return new DemoDataLoader(jdbc, ingestion, encoder, props, json);
	}

	static String resource(String path) throws IOException {
		return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
	}

	/** Best {@code n} chunks from this class's documents only; k=50 holds the whole demo corpus. */
	List<RetrievedChunk> top(String question, List<String> roles, int n) {
		return retriever.retrieve(question, roles, 50)
			.chunks()
			.stream()
			.filter(c -> docIdByFile.containsValue(c.documentId()))
			.limit(n)
			.toList();
	}

	/** Runs once, on first use, so a failure shows up in the test that needed it rather than as a setup error. */
	Eval evaluation() throws IOException {
		if (evaluation != null) {
			return evaluation;
		}
		List<String> misses = new ArrayList<>();
		double minOnTopic = Double.MAX_VALUE;
		double maxOffTopic = -Double.MAX_VALUE;
		var table = new StringBuilder(String.format(Locale.ROOT, "%n%-72s %-6s %s%n", "question", "rank", "top1"));
		List<String> lines = resource("eval/retrieval-questions.jsonl").lines().filter(l -> !l.isBlank()).toList();
		for (String line : lines) {
			Question q = json.readValue(line, Question.class);
			assertThat(docIdByFile).as("expectedFile of: %s", q.question()).containsKey(q.expectedFile());
			List<RetrievedChunk> top = top(q.question(), q.roles(), 5);
			long expected = docIdByFile.get(q.expectedFile());
			int rank = 0;
			for (int i = 0; i < top.size() && rank == 0; i++) {
				rank = top.get(i).documentId() == expected ? i + 1 : 0;
			}
			double top1 = top.isEmpty() ? 0 : top.get(0).score();
			if (rank == 0) {
				misses.add(q.question() + " (expected " + q.expectedFile() + ")");
			}
			minOnTopic = Math.min(minOnTopic, top1);
			table.append(String.format(Locale.ROOT, "%-72s %-6s %.3f%n", q.question(), rank > 0 ? rank : "miss", top1));
		}
		for (String q : resource("eval/offtopic-questions.txt").lines().filter(l -> !l.isBlank()).toList()) {
			double top1 = top(q, ALL_ROLES, 1).get(0).score();
			maxOffTopic = Math.max(maxOffTopic, top1);
			table.append(String.format(Locale.ROOT, "%-72s %-6s %.3f%n", q, "off", top1));
		}
		table.append(String.format(Locale.ROOT, "hits@5 %d/%d, min on-topic top1 %.3f, max off-topic top1 %.3f%n",
				lines.size() - misses.size(), lines.size(), minOnTopic, maxOffTopic));
		System.out.println(table);
		evaluation = new Eval(lines.size(), misses, minOnTopic, maxOffTopic);
		return evaluation;
	}

	/**
	 * The loader only seeds documents into an empty table, so this runs in a transaction that is rolled back: the
	 * DELETE and everything the loader writes (documents and demo users) never outlive the test.
	 */
	@Test
	@Transactional
	void demoLoaderSeedsUsersAndDocsOnce() throws Exception {
		jdbc.sql("DELETE FROM document").update();
		loader().run(null);
		loader().run(null); // second run: documents already present, so nothing is re-ingested
		assertThat(jdbc.sql("SELECT count(*) FROM document WHERE uploaded_by = 'demo-loader'")
			.query(Integer.class)
			.single()).isEqualTo(manifest.length);
		assertThat(jdbc.sql("SELECT username FROM app_user").query(String.class).list())
			.contains("admin", "hr.manager", "finance.analyst", "legal.counsel", "engineer");
		String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE username = 'legal.counsel'")
			.query(String.class)
			.single();
		assertThat(encoder.matches(props.demo().password(), hash)).isTrue();
	}

	/** Hit rate at top 5 must be at least 0.9, so at most a tenth of the questions may miss. */
	@Test
	void expectedDocumentIsInTopFive() throws IOException {
		Eval e = evaluation();
		assertThat(e.questions()).as("questions in eval/retrieval-questions.jsonl").isPositive();
		assertThat(e.misses()).as("questions whose expected doc is not in the top 5, out of %d", e.questions())
			.hasSizeLessThanOrEqualTo(e.questions() / 10);
	}

	@Test
	void minScoreSitsBetweenOffTopicAndOnTopicScores() throws IOException {
		Eval e = evaluation();
		double minScore = props.rag().minScore();
		assertThat(e.maxOffTopic()).as("max off-topic top-1 %.3f must be below app.rag.min-score %.3f",
				e.maxOffTopic(), minScore)
			.isLessThan(minScore);
		assertThat(e.minOnTopic()).as("min on-topic top-1 %.3f must be at least app.rag.min-score %.3f",
				e.minOnTopic(), minScore)
			.isGreaterThanOrEqualTo(minScore);
	}

}
