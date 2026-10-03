package com.harshpahurkar.rag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.TestJwt;

/** application-test.yml lifts the limits so other tests never trip them; this class puts them back down. */
@IntegrationTest
@TestPropertySource(properties = { "app.rate-limit.login.requests=10", "app.rate-limit.ask.requests=2",
		"app.rate-limit.search.requests=2", "app.rate-limit.upload.requests=2" })
@ExtendWith(OutputCaptureExtension.class)
class RateLimitIT {

	@Autowired
	MockMvc mvc;

	MockHttpServletRequestBuilder badLogin() {
		return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"rate.limit\",\"password\":\"guess\"}");
	}

	@Test
	void eleventhLoginInAWindowIs429WithRetryAfter(CapturedOutput output) throws Exception {
		for (int i = 0; i < 10; i++) {
			mvc.perform(badLogin()).andExpect(status().isUnauthorized());
		}
		MockHttpServletResponse res = mvc.perform(badLogin())
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.status").value(429))
			.andExpect(jsonPath("$.detail").exists())
			.andReturn()
			.getResponse();
		assertThat(Integer.parseInt(res.getHeader("Retry-After"))).isBetween(1, 60);
		// The limit and what it counts by, never the key itself (here the client IP).
		assertThat(SecurityAuditIT.audit(output)).contains("event=rate_limited limit=login key=ip path=/api/auth/login");
	}

	@Test
	void askIsLimitedPerUser() throws Exception {
		for (int i = 0; i < 2; i++) {
			assertThat(ask(mvc, "rl.first")).isNotEqualTo(429);
		}
		assertThat(ask(mvc, "rl.first")).isEqualTo(429);
		assertThat(ask(mvc, "rl.second")).isNotEqualTo(429);
	}

	@Test
	void searchIsLimitedPerUser() throws Exception {
		for (int i = 0; i < 2; i++) {
			assertThat(search("rl.searcher")).isEqualTo(200);
		}
		assertThat(search("rl.searcher")).isEqualTo(429);
		assertThat(search("rl.other.searcher")).isEqualTo(200);
	}

	/** Uploads with no roles fail validation (400) after the limiter counts them, so nothing is written. */
	@Test
	void uploadIsLimitedPerUserButListingIsNot() throws Exception {
		for (int i = 0; i < 2; i++) {
			assertThat(upload("rl.uploader")).isEqualTo(400);
		}
		assertThat(upload("rl.uploader")).isEqualTo(429);
		mvc.perform(get("/api/documents").with(TestJwt.as("rl.uploader", "ADMIN"))).andExpect(status().isOk());
		assertThat(upload("rl.other.uploader")).isEqualTo(400);
	}

	static int ask(MockMvc mvc, String username) throws Exception {
		return mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
			.content("{\"question\":\"What is the leave policy?\"}")
			.with(TestJwt.as(username, "EMPLOYEE"))).andReturn().getResponse().getStatus();
	}

	int search(String username) throws Exception {
		return mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
			.content("{\"query\":\"leave policy\"}")
			.with(TestJwt.as(username, "EMPLOYEE"))).andReturn().getResponse().getStatus();
	}

	int upload(String username) throws Exception {
		var file = new MockMultipartFile("file", "x.md", "text/markdown", "# Hi\n\nA note.".getBytes());
		return mvc.perform(multipart("/api/documents").file(file).with(TestJwt.as(username, "ADMIN")))
			.andReturn()
			.getResponse()
			.getStatus();
	}

	/** Its own context, so the shared daily budget starts full and the tests above can't spend it. */
	@Nested
	@TestPropertySource(properties = "app.rate-limit.ask-global.requests=3")
	class GlobalAskCap {

		@Autowired
		MockMvc mvc;

		@Test
		void askIsCappedAcrossAllUsers() throws Exception {
			for (int i = 1; i <= 3; i++) {
				assertThat(ask(mvc, "rl.global" + i)).isNotEqualTo(429);
			}
			// A user with no asks yet, so only the global cap can refuse it.
			assertThat(ask(mvc, "rl.global4")).isEqualTo(429);
		}

	}

}
