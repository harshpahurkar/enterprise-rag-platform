package com.harshpahurkar.rag;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.Arrays;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Signs a MockMvc request in as {@code username} holding {@code roles}, exactly as a real token would. */
public final class TestJwt {

	private TestJwt() {
	}

	public static RequestPostProcessor as(String username, String... roles) {
		return jwt().jwt(j -> j.subject(username).claim("roles", List.of(roles)))
			.authorities(Arrays.stream(roles).<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
	}

}
