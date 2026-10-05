package com.harshpahurkar.rag.document;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.harshpahurkar.rag.AppProperties;
import com.harshpahurkar.rag.search.PgVector;

import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

/** Tika parse, recursive split, in-process embed, then one transaction for the document and all its chunks. */
@Service
public class IngestionService {

	static final Set<String> VALID_ROLES = Set.of("ADMIN", "HR", "FINANCE", "LEGAL", "ENGINEERING", "EMPLOYEE");

	private static final String SELECT_VIEW = """
			SELECT d.id, d.title, d.filename, d.content_type, d.allowed_roles, d.uploaded_by, d.created_at,
			       (SELECT count(*) FROM chunk c WHERE c.document_id = d.id) AS chunk_count
			FROM document d
			""";

	private record Created(long id, OffsetDateTime at) {
	}

	private final ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();

	private final DocumentSplitter splitter;

	private final EmbeddingModel embeddings;

	private final JdbcClient jdbc;

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate tx;

	public IngestionService(AppProperties props, EmbeddingModel embeddings, JdbcClient jdbc, JdbcTemplate jdbcTemplate,
			TransactionTemplate tx) {
		this.splitter = DocumentSplitters.recursive(props.rag().chunkSize(), props.rag().chunkOverlap());
		this.embeddings = embeddings;
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.tx = tx;
	}

	public DocumentView ingest(String title, String filename, String contentType, InputStream content,
			List<String> allowedRoles, String uploadedBy) {
		if (allowedRoles == null || allowedRoles.isEmpty()) {
			throw badRequest("allowedRoles must name at least one role " + VALID_ROLES);
		}
		List<String> unknown = allowedRoles.stream().filter(r -> !VALID_ROLES.contains(r)).toList();
		if (!unknown.isEmpty()) {
			throw badRequest("Unknown role(s) " + unknown + "; valid roles are " + VALID_ROLES);
		}
		Document document;
		try {
			document = parser.parse(content);
		}
		catch (BlankDocumentException e) {
			throw badRequest("'" + filename + "' has no extractable text");
		}
		// Parse, split and embed before the transaction, so no connection is held during the slow part.
		List<TextSegment> segments = splitter.split(document);
		List<Embedding> vectors = embeddings.embedAll(segments).content();

		return tx.execute(status -> {
			var created = jdbc.sql("""
					INSERT INTO document (title, filename, content_type, allowed_roles, uploaded_by)
					VALUES (?, ?, ?, ?::text[], ?)
					RETURNING id, created_at
					""")
				.params(title, filename, contentType, allowedRoles.toArray(String[]::new), uploadedBy)
				.query((rs, n) -> new Created(rs.getLong(1), rs.getObject(2, OffsetDateTime.class)))
				.single();
			long id = created.id();
			jdbcTemplate.batchUpdate(
					"INSERT INTO chunk (document_id, chunk_index, content, embedding) VALUES (?, ?, ?, ?::vector)",
					IntStream.range(0, segments.size())
						.mapToObj(i -> new Object[] { id, i, segments.get(i).text(),
								PgVector.literal(vectors.get(i).vector()) })
						.toList());
			return new DocumentView(id, title, filename, contentType, List.copyOf(allowedRoles), uploadedBy,
					created.at().toInstant(), segments.size());
		});
	}

	/** Documents whose allowed roles overlap the caller's, newest first. */
	public List<DocumentView> listReadable(List<String> roles) {
		if (roles == null || roles.isEmpty()) {
			return List.of();
		}
		return jdbc.sql(SELECT_VIEW + " WHERE d.allowed_roles && ?::text[] ORDER BY d.created_at DESC, d.id DESC")
			.param(roles.toArray(String[]::new))
			.query(IngestionService::view)
			.list();
	}

	/** Chunks go with it (ON DELETE CASCADE). */
	public void delete(long id) {
		if (jdbc.sql("DELETE FROM document WHERE id = ?").param(id).update() == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document " + id + " not found");
		}
	}

	private static DocumentView view(ResultSet rs, int n) throws SQLException {
		return new DocumentView(rs.getLong("id"), rs.getString("title"), rs.getString("filename"),
				rs.getString("content_type"), List.of((String[]) rs.getArray("allowed_roles").getArray()),
				rs.getString("uploaded_by"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getInt("chunk_count"));
	}

	private static ResponseStatusException badRequest(String detail) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
	}

}
