package com.harshpahurkar.rag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.harshpahurkar.rag.IntegrationTest;

import tools.jackson.databind.json.JsonMapper;

/** Browser-facing headers, no-store caching, and proof that there is no CORS grant for another origin. */
@IntegrationTest
class SecurityHeadersIT {

	static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
			+ "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
			+ "frame-ancestors 'none'";

	static final String EVIL = "https://evil.example";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PasswordEncoder passwords;

	@BeforeEach
	void user() {
		deleteUser();
		jdbc.sql("INSERT INTO app_user (username, password_hash, roles) VALUES ('headers.user', ?, ARRAY['EMPLOYEE'])")
			.param(passwords.encode("headers-password"))
			.update();
	}

	@AfterEach
	void deleteUser() {
		jdbc.sql("DELETE FROM app_user WHERE username = 'headers.user'").update();
	}

	String token() throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"headers.user\",\"password\":\"headers-password\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonMapper.builder().build().readTree(body).get("token").asString();
	}

	void assertSecurityHeaders(ResultActions result) throws Exception {
		result.andExpect(header().string("Content-Security-Policy", CSP))
			.andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"))
			.andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
			.andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"));
	}

	@Test
	void spaAndApiResponsesCarryTheSecurityHeaders() throws Exception {
		assertSecurityHeaders(mvc.perform(get("/")));
		assertSecurityHeaders(
				mvc.perform(get("/api/documents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
					.andExpect(status().isOk()));
	}

	@Test
	void apiResponsesAreNeverStored() throws Exception {
		String token = token();
		mvc.perform(get("/api/documents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
		mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
			.content("{\"query\":\"leave policy\"}")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
	}

	/** The request is served (a browser would still send it), but no grant lets the other origin read the reply. */
	@Test
	void anotherOriginGetsNoCorsGrantEvenWithAValidToken() throws Exception {
		mvc.perform(get("/api/documents").header(HttpHeaders.ORIGIN, EVIL)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void preflightFromAnotherOriginIsRefused() throws Exception {
		MockHttpServletResponse res = mvc
			.perform(options("/api/documents/1").header(HttpHeaders.ORIGIN, EVIL)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
			.andReturn()
			.getResponse();
		assertThat(res.getStatus()).isGreaterThanOrEqualTo(400);
	}

}
