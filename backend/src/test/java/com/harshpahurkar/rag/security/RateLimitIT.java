package com.harshpahurkar.rag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.harshpahurkar.rag.IntegrationTest;
import com.harshpahurkar.rag.TestJwt;

/** application-test.yml lifts the limits so other tests never trip them; this class puts them back down. */
@IntegrationTest
@TestPropertySource(properties = { "app.rate-limit.login.requests=10", "app.rate-limit.ask.requests=2" })
class RateLimitIT {

	@Autowired
	MockMvc mvc;

	MockHttpServletRequestBuilder badLogin() {
		return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"rate.limit\",\"password\":\"guess\"}");
	}

	@Test
	void eleventhLoginInAWindowIs429WithRetryAfter() throws Exception {
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
	}

	@Test
	void askIsLimitedPerUser() throws Exception {
		for (int i = 0; i < 2; i++) {
			assertThat(ask("rl.first")).isNotEqualTo(429);
		}
		assertThat(ask("rl.first")).isEqualTo(429);
		assertThat(ask("rl.second")).isNotEqualTo(429);
	}

	int ask(String username) throws Exception {
		return mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
			.content("{\"question\":\"What is the leave policy?\"}")
			.with(TestJwt.as(username, "EMPLOYEE"))).andReturn().getResponse().getStatus();
	}

}
