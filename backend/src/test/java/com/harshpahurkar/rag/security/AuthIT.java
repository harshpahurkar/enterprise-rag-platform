package com.harshpahurkar.rag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.harshpahurkar.rag.AppProperties;
import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.TestJwt;
import com.harshpahurkar.rag.demo.DemoDataLoader;
import com.harshpahurkar.rag.document.IngestionService;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class AuthIT {

	static final List<String> DEMO_USERS = List.of("admin", "hr.manager", "finance.analyst", "legal.counsel",
			"engineer");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PasswordEncoder encoder;

	@Autowired
	AppProperties props;

	@Autowired
	IngestionService ingestion;

	@Autowired
	JsonMapper json;

	@BeforeEach
	void seedUser() {
		jdbc.sql("DELETE FROM app_user WHERE username = 'auth.tester'").update();
		jdbc.sql("INSERT INTO app_user (username, password_hash, roles) VALUES ('auth.tester', ?, ARRAY['HR','EMPLOYEE'])")
			.param(encoder.encode("correct horse"))
			.update();
	}

	String login() throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"auth.tester\",\"password\":\"correct horse\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("auth.tester"))
			.andExpect(jsonPath("$.roles", containsInAnyOrder("HR", "EMPLOYEE")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonMapper.builder().build().readTree(body).get("token").asString();
	}

	@Test
	void loginReturnsTokenThatAuthenticatesApiCalls() throws Exception {
		mvc.perform(get("/api/documents").header("Authorization", "Bearer " + login()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray());
	}

	@Test
	void meEndpointIsGone() throws Exception {
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + login()))
			.andExpect(status().isNotFound());
	}

	/** The login page's demo hint exists only under the demo profile; this context runs without it. */
	@Test
	void demoAccountsIsNotFoundWithoutTheDemoProfile() throws Exception {
		mvc.perform(get("/api/auth/demo-accounts")).andExpect(status().isNotFound());
	}

	@Test
	void wrongPasswordIs401() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"auth.tester\",\"password\":\"nope\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").exists());
	}

	@Test
	void unknownUserIs401() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"ghost\",\"password\":\"x\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void apiWithoutTokenIs401() throws Exception {
		mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\"}"))
			.andExpect(status().isUnauthorized());
	}

	/** A valid login token with one signature character changed. The first character carries 6 full bits. */
	@Test
	void tamperedSignatureIs401() throws Exception {
		String token = login();
		int sig = token.lastIndexOf('.') + 1;
		String tampered = token.substring(0, sig) + (token.charAt(sig) == 'A' ? 'B' : 'A') + token.substring(sig + 1);
		mvc.perform(get("/api/documents").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mvc.perform(get("/api/documents").header("Authorization", "Bearer " + tampered))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void nonAdminCannotUploadOrDelete() throws Exception {
		var file = new MockMultipartFile("file", "x.md", "text/markdown", "# hi".getBytes());
		mvc.perform(multipart("/api/documents").file(file).param("allowedRoles", "HR").with(TestJwt.as("hr", "HR")))
			.andExpect(status().isForbidden());
		mvc.perform(delete("/api/documents/1").with(TestJwt.as("hr", "HR"))).andExpect(status().isForbidden());
	}

	@Test
	void responsesCarryContentSecurityPolicy() throws Exception {
		mvc.perform(get("/api/documents").with(TestJwt.as("hr", "HR")))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
				.string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")));
	}

	DemoDataLoader demoLoader(String password) {
		var withPassword = new AppProperties(props.jwt(), props.rag(), props.llm(), new AppProperties.Demo(password));
		return new DemoDataLoader(jdbc, ingestion, encoder, withPassword, json);
	}

	@Test
	void demoLoaderRefusesBlankPassword() {
		assertThatThrownBy(() -> demoLoader(" ")).hasMessageContaining("DEMO_PASSWORD");
	}

	/** A restart must not undo a password or role change made after the first start. */
	@Test
	void demoLoaderOnlyCreatesMissingUsers() throws Exception {
		DEMO_USERS.forEach(u -> jdbc.sql("DELETE FROM app_user WHERE username = ?").param(u).update());
		jdbc.sql("INSERT INTO app_user (username, password_hash, roles) VALUES ('hr.manager', ?, ARRAY['EMPLOYEE'])")
			.param(encoder.encode("changed by hr"))
			.update();
		// A document row makes the loader skip the demo documents, so only the user step runs.
		long guard = jdbc.sql("""
				INSERT INTO document (title, filename, allowed_roles, uploaded_by)
				VALUES ('loader guard', 'guard.md', ARRAY['ADMIN'], 'auth-it') RETURNING id
				""").query(Long.class).single();
		try {
			demoLoader("demo-pass").run(null);

			assertThat(encoder.matches("changed by hr", passwordHash("hr.manager"))).isTrue();
			assertThat(roles("hr.manager")).containsExactly("EMPLOYEE");
			assertThat(encoder.matches("demo-pass", passwordHash("engineer"))).isTrue();
			assertThat(roles("admin")).containsExactlyElementsOf(Roles.ALL);
		}
		finally {
			jdbc.sql("DELETE FROM document WHERE id = ?").param(guard).update();
			DEMO_USERS.forEach(u -> jdbc.sql("DELETE FROM app_user WHERE username = ?").param(u).update());
		}
	}

	String passwordHash(String username) {
		return jdbc.sql("SELECT password_hash FROM app_user WHERE username = ?").param(username).query(String.class).single();
	}

	List<String> roles(String username) {
		return jdbc.sql("SELECT roles FROM app_user WHERE username = ?")
			.param(username)
			.query((rs, n) -> List.of((String[]) rs.getArray(1).getArray()))
			.single();
	}

}
