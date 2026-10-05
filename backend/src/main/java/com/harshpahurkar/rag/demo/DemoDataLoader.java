package com.harshpahurkar.rag.demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.harshpahurkar.rag.AppProperties;
import com.harshpahurkar.rag.document.DocumentView;
import com.harshpahurkar.rag.document.IngestionService;
import com.harshpahurkar.rag.security.Roles;

import tools.jackson.databind.json.JsonMapper;

/**
 * Demo profile only: creates any missing demo user (existing ones keep their password and roles), and ingests
 * demo-docs/ when the document table is empty.
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

	private static final Map<String, List<String>> USERS = Map.of(
			"admin", Roles.ALL,
			"hr.manager", List.of("HR", "EMPLOYEE"),
			"finance.analyst", List.of("FINANCE", "EMPLOYEE"),
			"legal.counsel", List.of("LEGAL", "EMPLOYEE"),
			"engineer", List.of("ENGINEERING", "EMPLOYEE"));

	record ManifestEntry(String file, String title, List<String> allowedRoles) {
	}

	private final JdbcClient jdbc;

	private final IngestionService ingestion;

	private final PasswordEncoder encoder;

	private final AppProperties props;

	private final JsonMapper json;

	public DemoDataLoader(JdbcClient jdbc, IngestionService ingestion, PasswordEncoder encoder, AppProperties props,
			JsonMapper json) {
		if (!StringUtils.hasText(props.demo().password())) {
			throw new IllegalStateException("The demo profile needs app.demo.password (DEMO_PASSWORD) to be set");
		}
		this.jdbc = jdbc;
		this.ingestion = ingestion;
		this.encoder = encoder;
		this.props = props;
		this.json = json;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		USERS.forEach((username, roles) -> jdbc.sql("""
				INSERT INTO app_user (username, password_hash, roles) VALUES (?, ?, ?::text[])
				ON CONFLICT (username) DO NOTHING
				""").params(username, encoder.encode(props.demo().password()), roles.toArray(String[]::new)).update());
		log.info("Demo users present: {} (existing ones keep their password and roles)", USERS.keySet());

		if (jdbc.sql("SELECT count(*) FROM document").query(Long.class).single() > 0) {
			log.info("Documents already present, skipping demo documents");
			return;
		}
		ManifestEntry[] manifest;
		try (InputStream in = new ClassPathResource("demo-docs/manifest.json").getInputStream()) {
			manifest = json.readValue(in, ManifestEntry[].class);
		}
		for (ManifestEntry entry : manifest) {
			try (InputStream in = new ClassPathResource("demo-docs/" + entry.file()).getInputStream()) {
				DocumentView doc = ingestion.ingest(entry.title(), entry.file(), "text/markdown", in,
						entry.allowedRoles(), "demo-loader");
				log.info("Demo document '{}' ingested: {} chunks, roles {}", doc.title(), doc.chunkCount(),
						doc.allowedRoles());
			}
		}
	}

}
