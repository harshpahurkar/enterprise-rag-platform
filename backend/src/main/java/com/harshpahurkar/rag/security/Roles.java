package com.harshpahurkar.rag.security;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** The one list of roles, and the caller's roles as plain names. */
public final class Roles {

	public static final List<String> ALL = List.of("ADMIN", "HR", "FINANCE", "LEGAL", "ENGINEERING", "EMPLOYEE");

	private Roles() {
	}

	/** Role names without the ROLE_ prefix; other authorities (e.g. Security 7's FACTOR_PASSWORD) are skipped. */
	public static List<String> of(Authentication auth) {
		return auth.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(a -> a.startsWith("ROLE_"))
			.map(a -> a.substring("ROLE_".length()))
			.toList();
	}

}
