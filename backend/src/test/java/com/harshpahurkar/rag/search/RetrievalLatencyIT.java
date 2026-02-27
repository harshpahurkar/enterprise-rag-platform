package com.harshpahurkar.rag.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.dockerjava.api.model.Info;
import com.harshpahurkar.rag.TestcontainersConfiguration;

import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Checks the "sub-200ms query retrieval" claim: 100k chunks, then 500 natural-language queries through the real
 * {@link Retriever} as an ENGINEERING + EMPLOYEE user. Writes target/benchmark/retrieval-latency.md.
 * Run with {@code ./mvnw test -Pbenchmark}.
 */
@Tag("benchmark")
@SpringBootTest
@ActiveProfiles("test")
class RetrievalLatencyIT {

	private static final List<String> ROLES = List.of("ENGINEERING", "EMPLOYEE");

	private static final int K = 6;

	private static final int WARMUP = 50;

	private static final Path REPORT = Path.of("target", "benchmark", "retrieval-latency.md");

	/** Read from Retriever so the timed SQL and the EXPLAIN can't drift from production. */
	private static final String SEARCH_SQL = (String) ReflectionTestUtils.getField(Retriever.class, "SEARCH_SQL");

	/**
	 * Each role is attached with p = 0.15 and a document left with none gets HR, FINANCE or LEGAL, so about 28% of
	 * documents carry ENGINEERING or EMPLOYEE. setseed() runs first, so the data is the same every run.
	 */
	private static final String INSERT_DOCUMENTS = """
			INSERT INTO document (title, filename, content_type, allowed_roles, uploaded_by)
			SELECT 'Synthetic document ' || i, 'synthetic-' || i || '.md', 'text/markdown',
			       COALESCE(NULLIF(array_remove(ARRAY[
			           CASE WHEN random() < 0.15 THEN 'HR' END,
			           CASE WHEN random() < 0.15 THEN 'FINANCE' END,
			           CASE WHEN random() < 0.15 THEN 'LEGAL' END,
			           CASE WHEN random() < 0.15 THEN 'ENGINEERING' END,
			           CASE WHEN random() < 0.15 THEN 'EMPLOYEE' END], NULL), '{}'),
			         ARRAY[(ARRAY['HR', 'FINANCE', 'LEGAL'])[1 + floor(random() * 3)::int]]),
			       'benchmark'
			FROM generate_series(1, 1000) i
			""";

	/** 1,000 bodies of about 1,000 characters (the production chunk size), so rows TOAST like real chunks. */
	private static final String CREATE_TEXT_POOL = """
			CREATE TEMP TABLE text_pool ON COMMIT DROP AS
			SELECT n, string_agg((ARRAY['policy', 'approval', 'deployment', 'budget', 'review', 'contract', 'employee',
			    'manager', 'quarter', 'report', 'incident', 'security', 'access', 'vendor', 'client', 'process', 'request',
			    'deadline', 'team', 'training', 'salary', 'leave', 'expense', 'audit', 'compliance', 'release', 'rollback',
			    'service', 'database', 'customer', 'invoice', 'payment', 'benefit', 'handbook', 'office', 'remote', 'travel',
			    'risk', 'legal', 'finance', 'engineering', 'the', 'a', 'of', 'and', 'to', 'for', 'with', 'must',
			    'should'])[1 + floor(random() * 50)::int], ' ') AS body
			FROM generate_series(1, 1000) n, generate_series(1, 140) w
			GROUP BY n
			""";

	/** 100 chunks per document. random_normal() then l2_normalize gives directions uniform on the sphere. */
	private static final String INSERT_CHUNKS = """
			INSERT INTO chunk (document_id, chunk_index, content, embedding)
			SELECT d.id, g.i, 'Document ' || d.id || ', chunk ' || g.i || ': ' || p.body,
			       -- g.i makes the subquery correlated, so it runs per row instead of once
			       (SELECT l2_normalize(array_agg(random_normal())::vector) FROM generate_series(1, 384) WHERE g.i >= 0)
			FROM document d
			CROSS JOIN generate_series(0, 99) g(i)
			JOIN text_pool p ON p.n = 1 + (d.id * 100 + g.i) % 1000
			""";

	private static final List<String> TEMPLATES = List.of("What is the policy on %s?",
			"How do I request approval for %s?", "Who is responsible for %s in our team?",
			"Summarize the latest guidance about %s", "What changed this year regarding %s?",
			"Is there a checklist for %s?", "What are the common mistakes with %s?",
			"Explain the escalation path for %s", "Where can I find the documentation for %s?",
			"What deadlines apply to %s?", "How does %s affect new hires?",
			"What are the security requirements for %s?", "Give me the step by step process for %s",
			"Which tools do we use for %s?", "What is the budget limit for %s?", "How should managers handle %s?",
			"What does the handbook say about %s?", "Are contractors covered by the rules on %s?",
			"What metrics do we track for %s?", "How often is %s reviewed?",
			"What happens if someone violates the rules on %s?", "Can I get an exception for %s?",
			"What training is required before %s?", "List the approvers needed for %s",
			"What is the difference between the old and new process for %s?", "How do remote employees handle %s?",
			"What are the legal risks around %s?", "Who should I contact with questions about %s?",
			"What templates exist for %s?", "How long does %s usually take?",
			"What evidence do auditors expect for %s?", "Describe the on-call expectations related to %s",
			"What is the rollback plan for %s?", "How do we document decisions about %s?",
			"What are the cost controls for %s?", "Which teams are affected by %s?",
			"What is the recommended practice for %s?", "How are disputes about %s resolved?",
			"What data can be shared when dealing with %s?", "Is %s mandatory for all departments?",
			"What are the key dates for %s this quarter?", "How is performance measured for %s?",
			"What should I do first when %s goes wrong?", "Give an example of a good request for %s",
			"What compliance rules apply to %s?", "How do I report a problem with %s?",
			"What are the limits on %s for interns?", "Which forms do I need to fill out for %s?",
			"What is the history behind our approach to %s?", "How do other offices handle %s?");

	/** The first subject is the warm-up set; the other 10 give the 500 measured queries. */
	private static final List<String> SUBJECTS = List.of("office supplies", "remote work", "expense reimbursement",
			"production deployments", "parental leave", "vendor contracts", "incident response", "salary reviews",
			"quarterly financial reporting", "password rotation", "client data retention");

	/** Own container: parallel HNSW builds need /dev/shm of at least maintenance_work_mem; Docker's default is 64 MB. */
	@TestConfiguration(proxyBeanMethods = false)
	static class LargeShmPostgres {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(TestcontainersConfiguration.PGVECTOR).withSharedMemorySize(1024L * 1024 * 1024);
		}

	}

	@Autowired
	Retriever retriever;

	@Autowired
	EmbeddingModel embeddingModel;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate tx;

	@Test
	void p95RetrievalIsUnder200msAt100kChunks() throws IOException {
		long t = System.nanoTime();
		tx.executeWithoutResult(s -> {
			// pgvector README: building the graph after the load is much faster than inserting into it row by row.
			jdbc.sql("DROP INDEX chunk_embedding_hnsw").update();
			jdbc.sql("SELECT setseed(0.42)").query().singleRow();
			jdbc.sql(INSERT_DOCUMENTS).update();
			jdbc.sql(CREATE_TEXT_POOL).update();
			jdbc.sql(INSERT_CHUNKS).update();
		});
		double loadSeconds = secondsSince(t);

		t = System.nanoTime();
		tx.executeWithoutResult(s -> {
			jdbc.sql("SET LOCAL maintenance_work_mem = '1GB'").update();
			jdbc.sql("CREATE INDEX chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops)").update();
		});
		double indexSeconds = secondsSince(t);

		// VACUUM as well as ANALYZE: sets hint bits now and stops autovacuum from waking up mid-run.
		t = System.nanoTime();
		jdbc.sql("VACUUM (ANALYZE) document, chunk").update();
		double vacuumSeconds = secondsSince(t);

		List<String> queries = new ArrayList<>();
		SUBJECTS.forEach(subject -> TEMPLATES.forEach(template -> queries.add(template.formatted(subject))));
		queries.subList(0, WARMUP).forEach(q -> retriever.retrieve(q, ROLES, K));

		int n = queries.size() - WARMUP;
		double[] total = new double[n];
		double[] embed = new double[n];
		double[] sql = new double[n];
		double[] roundTrip = new double[n];
		int shortResults = 0;
		for (int i = 0; i < n; i++) {
			String q = queries.get(WARMUP + i);
			Retriever.Retrieval r = retriever.retrieve(q, ROLES, K);
			total[i] = r.retrievalMs();
			if (r.chunks().size() != K) {
				shortResults++;
			}
			t = System.nanoTime();
			String vector = PgVector.literal(embeddingModel.embed(q).content().vector());
			embed[i] = millisSince(t);
			t = System.nanoTime();
			search(vector);
			sql[i] = millisSince(t);
			t = System.nanoTime();
			jdbc.sql("SELECT 1").query().singleValue();
			roundTrip[i] = millisSince(t);
		}

		String plan = explain(PgVector
			.literal(embeddingModel.embed("How do I roll back a failed production deployment?").content().vector()));

		Map<String, Object> db = jdbc.sql("""
				SELECT (SELECT count(*) FROM document) AS documents,
				       (SELECT count(*) FROM chunk) AS chunks,
				       (SELECT avg((d.allowed_roles && :roles::text[])::int)
				          FROM chunk c JOIN document d ON d.id = c.document_id) AS visible,
				       pg_size_pretty(pg_table_size('chunk')) AS chunk_table,
				       pg_size_pretty(pg_relation_size('chunk_embedding_hnsw')) AS hnsw_index,
				       current_setting('server_version') AS postgres,
				       (SELECT extversion FROM pg_extension WHERE extname = 'vector') AS pgvector,
				       current_setting('shared_buffers') AS shared_buffers,
				       current_setting('hnsw.ef_search', true) AS ef_search
				""").param("roles", ROLES.toArray(String[]::new)).query().singleRow();
		double visible = ((Number) db.get("visible")).doubleValue();
		Info docker = DockerClientFactory.instance().getInfo();

		String report = String.format(Locale.ROOT, """
				# Retrieval latency

				Run %s. %d queries after %d warm-up queries, k = %d, user roles %s.

				| Stage | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
				|---|---:|---:|---:|---:|
				%s
				%s
				%s
				%s

				Total is `retrievalMs` from `Retriever.retrieve`, in whole ms. After each retrieve, the embed, the SQL and
				a bare `SELECT 1` are timed alone with `System.nanoTime`, in that order. The SQL makes three round trips
				(SET LOCAL, search, COMMIT); the EXPLAIN below shows how much of it Postgres spends executing.

				## Dataset

				- %s chunks across %s documents; %.1f%% of chunks are readable by %s
				- random unit-normalized 384-d vectors, chunk text of about 1,000 characters
				- load %.1f s; HNSW build %.1f s (`maintenance_work_mem = 1GB`, after the load); VACUUM ANALYZE %.1f s
				- chunk table (heap + TOAST) %s; `chunk_embedding_hnsw` %s
				- PostgreSQL %s, pgvector %s, shared_buffers %s, hnsw.ef_search %s, hnsw.iterative_scan strict_order

				## Machine

				- JVM: %d available processors, %s (%s), Java %s
				- Docker: %d CPUs, %.1f GB memory (%s)
				- CPU model: (fill in)

				## EXPLAIN (ANALYZE, BUFFERS) of the search, as Retriever runs it

				```
				%s
				```
				""", LocalDate.now(), n, WARMUP, K, ROLES, row("Total (retrievalMs)", total),
				row("Embed query (BGE-small-en-v1.5 q, in-process)", embed),
				row("SQL (role-filtered HNSW search, one transaction)", sql),
				row("DB round trip (`SELECT 1`)", roundTrip), db.get("chunks"), db.get("documents"), visible * 100,
				ROLES, loadSeconds, indexSeconds, vacuumSeconds, db.get("chunk_table"), db.get("hnsw_index"),
				db.get("postgres"), db.get("pgvector"), db.get("shared_buffers"), db.get("ef_search"),
				Runtime.getRuntime().availableProcessors(), System.getProperty("os.name"), System.getProperty("os.arch"),
				System.getProperty("java.version"), docker.getNCPU(), docker.getMemTotal() / 1e9,
				docker.getOperatingSystem(), plan);
		Files.createDirectories(REPORT.getParent());
		Files.writeString(REPORT, report);
		System.out.println(report);

		assertThat(visible).as("share of chunks the user can read").isBetween(0.2, 0.4);
		assertThat(shortResults).as("queries that returned fewer than %d chunks", K).isZero();
		assertThat(plan).as("search plan").contains("Index Scan using chunk_embedding_hnsw");
		assertThat(percentile(total, 95)).as("p95 retrievalMs").isLessThan(200);
	}

	/** SEARCH_SQL exactly as Retriever runs it: one transaction with the iterative scan on. */
	private void search(String vector) {
		tx.executeWithoutResult(s -> {
			jdbc.sql("SET LOCAL hnsw.iterative_scan = strict_order").update();
			bind(jdbc.sql(SEARCH_SQL), vector).query().listOfRows();
		});
	}

	/** EXPLAIN inlines the bound query vector; shorten it so the plan stays readable. */
	private String explain(String vector) {
		return tx.execute(s -> {
			jdbc.sql("SET LOCAL hnsw.iterative_scan = strict_order").update();
			return String.join("\n",
					bind(jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + SEARCH_SQL), vector).query(String.class).list())
				.replaceAll("'\\[[^\\]]*\\]'::vector", "'[384 floats]'::vector");
		});
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, String vector) {
		return spec.param("q", vector).param("roles", ROLES.toArray(String[]::new)).param("k", K);
	}

	private static String row(String stage, double[] ms) {
		return String.format(Locale.ROOT, "| %s | %.1f | %.1f | %.1f | %.1f |", stage, percentile(ms, 50),
				percentile(ms, 95), percentile(ms, 99), percentile(ms, 100));
	}

	/** Nearest-rank percentile. */
	private static double percentile(double[] values, double p) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[(int) Math.ceil(p / 100 * sorted.length) - 1];
	}

	private static double millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1e6;
	}

	private static double secondsSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1e9;
	}

}
