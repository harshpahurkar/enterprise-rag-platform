package com.harshpahurkar.rag.search;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Embeds a query in-process and runs the role-filtered pgvector search.
 * retrieve(query, userRoles, k) returns at most k chunks the caller may read, best first,
 * plus how long embedding + SQL took.
 */
@Service
public class Retriever {

	public record RetrievedChunk(long chunkId, long documentId, String title, int chunkIndex, String content,
			double score) {
	}

	public record Retrieval(List<RetrievedChunk> chunks, long retrievalMs) {
	}

	/**
	 * RBAC lives in the WHERE clause, so a chunk the caller can't read is never fetched and can't
	 * reach a prompt. ORDER BY the bare distance expression is what lets Postgres use the HNSW index.
	 */
	private static final String SEARCH_SQL = """
			SELECT c.id, c.document_id, d.title, c.chunk_index, c.content,
			       1 - (c.embedding <=> :q::vector) AS score
			FROM chunk c
			JOIN document d ON d.id = c.document_id
			WHERE d.allowed_roles && :roles::text[]
			ORDER BY c.embedding <=> :q::vector
			LIMIT :k
			""";

	private final EmbeddingModel embeddings;

	private final JdbcClient jdbc;

	public Retriever(EmbeddingModel embeddings, JdbcClient jdbc) {
		this.embeddings = embeddings;
		this.jdbc = jdbc;
	}

	public Retrieval retrieve(String query, List<String> userRoles, int k) {
		long start = System.nanoTime();
		if (userRoles == null || userRoles.isEmpty()) {
			return new Retrieval(List.of(), 0);
		}
		String vector = PgVector.literal(embeddings.embed(query).content().vector());
		// One round trip. hnsw.iterative_scan and plan_cache_mode are set per connection (application.yml).
		List<RetrievedChunk> chunks = jdbc.sql(SEARCH_SQL)
			.param("q", vector)
			.param("roles", userRoles.toArray(String[]::new))
			.param("k", k)
			.query((rs, n) -> new RetrievedChunk(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
					rs.getString(5), rs.getDouble(6)))
			.list();
		return new Retrieval(chunks, (System.nanoTime() - start) / 1_000_000);
	}

}
