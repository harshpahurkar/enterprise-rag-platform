package com.harshpahurkar.rag.security;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.TestJwt;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class AuthIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PasswordEncoder encoder;

	@BeforeEach
	void seedUser() {
		jdbc.sql("DELETE FROM app_user WHERE username = 'auth.tester'").update();
		jdbc.sql("INSERT INTO app_user (username, password_hash, roles) VALUES ('auth.tester', ?, ARRAY['HR','EMPLOYEE'])")
			.param(encoder.encode("correct horse"))
			.update();
	}

	@Test
	void loginReturnsTokenThatAuthenticatesApiCalls() throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"auth.tester\",\"password\":\"correct horse\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("auth.tester"))
			.andExpect(jsonPath("$.roles", containsInAnyOrder("HR", "EMPLOYEE")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String token = JsonMapper.builder().build().readTree(body).get("token").asString();

		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("auth.tester"))
			.andExpect(jsonPath("$.roles", containsInAnyOrder("HR", "EMPLOYEE")));
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

	@Test
	void tamperedTokenIs401() throws Exception {
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.bad"))
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
		mvc.perform(get("/api/auth/me").with(TestJwt.as("hr", "HR")))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
				.string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")));
	}

}
