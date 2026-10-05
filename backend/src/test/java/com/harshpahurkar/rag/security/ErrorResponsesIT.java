package com.harshpahurkar.rag.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.harshpahurkar.rag.TestcontainersConfiguration;

/**
 * Error responses through a real servlet container, because MockMvc never forwards to /error. Test-only endpoints
 * stand in for a bug that throws and for a method-level check that is stricter than its URL rule.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ErrorResponsesIT {

	@RestController
	static class ProbeController {

		/** /api/** only requires a signed-in user, so this method's own check is the only ADMIN gate. */
		@PreAuthorize("hasRole('ADMIN')")
		@GetMapping("/api/test/admin-only")
		String adminOnly() {
			return "ok";
		}

		/** A bug whose message holds a secret, as a driver or library exception might. */
		@GetMapping("/api/test/boom")
		String boom() {
			throw new RuntimeException("db password is hunter2");
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	@Import(ProbeController.class)
	static class Probes {

	}

	@LocalServerPort
	int port;

	@Autowired
	JwtEncoder encoder;

	@Autowired
	JdbcClient jdbc;

	final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void users() {
		deleteUsers();
		jdbc.sql("INSERT INTO app_user (username, password_hash, roles) VALUES ('err.admin', 'x', ARRAY['ADMIN'])")
			.update();
		jdbc.sql("INSERT INTO app_user (username, password_hash, roles) VALUES ('err.hr', 'x', ARRAY['HR'])").update();
	}

	@AfterEach
	void deleteUsers() {
		jdbc.sql("DELETE FROM app_user WHERE username IN ('err.admin', 'err.hr')").update();
	}

	String token(String username) {
		Instant now = Instant.now();
		var claims = JwtClaimsSet.builder()
			.issuer(SecurityConfig.ISSUER)
			.subject(username)
			.issuedAt(now)
			.expiresAt(now.plusSeconds(300))
			.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	HttpResponse<String> get(String path, String username) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Authorization", "Bearer " + token(username))
			.header("Accept", "application/json")
			.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void unhandledErrorIsA500WithoutMessageOrClassNames() throws Exception {
		HttpResponse<String> res = get("/api/test/boom", "err.hr");
		assertThat(res.statusCode()).isEqualTo(500);
		assertThat(res.body()).doesNotContain("hunter2", "Exception", "java.", "ProbeController", "trace");
		assertThat(res.headers().map().toString()).doesNotContain("hunter2", "Exception");
	}

	@Test
	void methodSecurityDenialIs403NotA500() throws Exception {
		assertThat(get("/api/test/admin-only", "err.hr").statusCode()).isEqualTo(403);
		assertThat(get("/api/test/admin-only", "err.admin").statusCode()).isEqualTo(200);
	}

}
