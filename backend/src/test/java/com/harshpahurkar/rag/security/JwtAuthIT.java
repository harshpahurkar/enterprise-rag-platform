package com.harshpahurkar.rag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.document.IngestionService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import tools.jackson.databind.json.JsonMapper;

/** Real tokens only: issued by POST /api/auth/login or signed with the app's own encoder. No mocked JWTs. */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JwtAuthIT {

	static final String PASSWORD = "jwt-it-password";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PasswordEncoder passwords;

	@Autowired
	JwtEncoder encoder;

	@Autowired
	JwtDecoder decoder;

	@Autowired
	IngestionService ingestion;

	long hrDoc;

	long financeDoc;

	final List<Long> createdDocs = new ArrayList<>();

	@BeforeAll
	void documents() {
		hrDoc = ingest("JWT test HR memo", "HR");
		financeDoc = ingest("JWT test finance memo", "FINANCE");
	}

	@AfterAll
	void deleteDocuments() {
		createdDocs.forEach(id -> jdbc.sql("DELETE FROM document WHERE id = ?").param(id).update());
	}

	@BeforeEach
	void users() {
		deleteUsers();
		createUser("jwt.admin", "ADMIN");
		createUser("jwt.hr", "HR");
	}

	@AfterEach
	void deleteUsers() {
		jdbc.sql("DELETE FROM app_user WHERE username IN ('jwt.admin', 'jwt.hr')").update();
	}

	long ingest(String title, String role) {
		byte[] text = ("# " + title + "\n\nThis memo exists only for the JWT integration test.").getBytes();
		long id = ingestion.ingest(title, "memo.md", "text/markdown", new ByteArrayInputStream(text), List.of(role),
				"jwt-it")
			.id();
		createdDocs.add(id);
		return id;
	}

	void createUser(String username, String... roles) {
		jdbc.sql("INSERT INTO app_user (username, password_hash, roles) VALUES (?, ?, ?::text[])")
			.params(username, passwords.encode(PASSWORD), roles)
			.update();
	}

	String login(String username) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonMapper.builder().build().readTree(body).get("token").asString();
	}

	/** A token for jwt.admin with every claim the app checks; {@code tweak} breaks exactly one of them. */
	static String mint(JwtEncoder signer, Consumer<Map<String, Object>> tweak) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("rag-platform")
			.subject("jwt.admin")
			.issuedAt(now)
			.expiresAt(now.plusSeconds(300))
			.claims(tweak)
			.build();
		return signer.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	ResultActions listDocuments(String token) throws Exception {
		return mvc.perform(get("/api/documents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	ResultActions search(String token) throws Exception {
		return mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
			.content("{\"query\":\"JWT test memo\",\"k\":50}")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
	}

	MockMultipartFile markdown() {
		return new MockMultipartFile("file", "jwt-upload.md", "text/markdown", "# Upload\n\nA short note.".getBytes());
	}

	@Test
	void loginTokenCarriesIssuerAndExpiryButNoRoles() throws Exception {
		Jwt jwt = decoder.decode(login("jwt.hr"));
		assertThat(jwt.getClaims()).doesNotContainKey("roles");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("rag-platform");
		assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
	}

	@Test
	void adminTokenCanUpload() throws Exception {
		String body = mvc
			.perform(multipart("/api/documents").file(markdown())
				.param("allowedRoles", "HR")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + login("jwt.admin")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.uploadedBy").value("jwt.admin"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		createdDocs.add(JsonMapper.builder().build().readTree(body).get("id").asLong());
	}

	@Test
	void hrTokenCannotUploadAndSeesOnlyHrDocuments() throws Exception {
		String token = login("jwt.hr");
		mvc.perform(multipart("/api/documents").file(markdown())
			.param("allowedRoles", "HR")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isForbidden());
		listDocuments(token).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == %d)]", hrDoc).isNotEmpty())
			.andExpect(jsonPath("$[?(@.id == %d)]", financeDoc).isEmpty())
			.andExpect(jsonPath("$[?(@.allowedRoles noneof ['HR'])]").isEmpty());
	}

	@Test
	void roleChangeAppliesToDocumentListImmediately() throws Exception {
		String token = login("jwt.hr");
		listDocuments(token).andExpect(jsonPath("$[?(@.id == %d)]", hrDoc).isNotEmpty())
			.andExpect(jsonPath("$[?(@.id == %d)]", financeDoc).isEmpty());

		jdbc.sql("UPDATE app_user SET roles = ARRAY['FINANCE'] WHERE username = 'jwt.hr'").update();

		listDocuments(token).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == %d)]", financeDoc).isNotEmpty())
			.andExpect(jsonPath("$[?(@.id == %d)]", hrDoc).isEmpty());
	}

	@Test
	void roleChangeAppliesToSearchImmediately() throws Exception {
		String token = login("jwt.hr");
		search(token).andExpect(jsonPath("$.results[?(@.documentId == %d)]", hrDoc).isNotEmpty())
			.andExpect(jsonPath("$.results[?(@.documentId == %d)]", financeDoc).isEmpty());

		jdbc.sql("UPDATE app_user SET roles = ARRAY['FINANCE'] WHERE username = 'jwt.hr'").update();

		search(token).andExpect(jsonPath("$.results[?(@.documentId == %d)]", financeDoc).isNotEmpty())
			.andExpect(jsonPath("$.results[?(@.documentId == %d)]", hrDoc).isEmpty());
	}

	@Test
	void deletedUserTokenIs401() throws Exception {
		String token = login("jwt.hr");
		listDocuments(token).andExpect(status().isOk());
		jdbc.sql("DELETE FROM app_user WHERE username = 'jwt.hr'").update();
		listDocuments(token).andExpect(status().isUnauthorized());
	}

	/** Baseline for the tests below: each breaks one thing in a token that otherwise works. */
	@Test
	void mintedTokenWithEveryClaimWorks() throws Exception {
		listDocuments(mint(encoder, c -> {
		})).andExpect(status().isOk());
	}

	@Test
	void tokenSignedWithAnotherKeyIs401() throws Exception {
		byte[] otherKey = "another-32-byte-key-for-jwt-it!!".getBytes(StandardCharsets.UTF_8);
		assertThat(otherKey).hasSize(32);
		var otherSigner = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
		listDocuments(mint(otherSigner, c -> {
		})).andExpect(status().isUnauthorized());
	}

	@Test
	void expiredTokenIs401() throws Exception {
		Instant now = Instant.now();
		listDocuments(mint(encoder, c -> {
			c.put("iat", now.minusSeconds(7200));
			c.put("exp", now.minusSeconds(3600));
		})).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenWithoutExpiryIs401() throws Exception {
		listDocuments(mint(encoder, c -> c.remove("exp"))).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenFromAnotherIssuerIs401() throws Exception {
		listDocuments(mint(encoder, c -> c.put("iss", "someone-else"))).andExpect(status().isUnauthorized());
	}

	@Test
	void algNoneTokenIs401() throws Exception {
		var b64 = Base64.getUrlEncoder().withoutPadding();
		String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
		String claims = b64.encodeToString(("{\"iss\":\"rag-platform\",\"sub\":\"jwt.admin\",\"exp\":"
				+ Instant.now().plusSeconds(300).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
		listDocuments(header + "." + claims + ".").andExpect(status().isUnauthorized());
	}

	@Test
	void nonNumericDocumentIdIs400() throws Exception {
		mvc.perform(delete("/api/documents/abc").header(HttpHeaders.AUTHORIZATION, "Bearer " + login("jwt.admin")))
			.andExpect(status().isBadRequest());
	}

}
